package io.onda.sdk

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 코어 오케스트레이터 — 식별자·큐·네트워크·플러시·푸시를 조율. iOS OndaCore와 대칭.
 * 모든 상태 변경은 단일 워커 스레드에서 수행(공개 API 논블로킹).
 */
internal class OndaCore(
    context: Context,
    private val config: OndaConfig,
) {
    private val prefs = context.getSharedPreferences("onda", Context.MODE_PRIVATE)
    private val identity = Identity(prefs)
    private val queue = EventQueue(File(context.filesDir, "onda_events.json"))
    private val network = Network(config, identity.deviceId)
    private val push = PushManager(network, prefs)
    val bus = EventBus { r -> android.os.Handler(android.os.Looper.getMainLooper()).post(r) }

    private val work = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var flushing = false

    val anonId: String get() = identity.anonId
    val deviceId: String get() = identity.deviceId

    fun start() {
        OndaLog.info("Onda 초기화: host=${config.apiHost}")
        if (config.autoTrackSessions) track("session_start", emptyMap())
        scheduler.scheduleWithFixedDelay(
            { flushSync() }, config.flushIntervalSeconds, config.flushIntervalSeconds, TimeUnit.SECONDS,
        )
        flush() // 이전 세션 잔존분 즉시 전송 시도
    }

    fun identify(externalId: String) = work.execute {
        identity.externalId = externalId
        network.sendIdentify(externalId, identity.anonId, emptyMap()) { ok ->
            OndaLog.info("identify ${if (ok) "성공" else "재시도 대기"}")
        }
    }

    fun reset() = work.execute {
        flushSync() // 이전 유저 이벤트를 먼저 비운다
        identity.reset()
        push.clearTokenCache() // 다음 토큰을 새 유저로 재등록 (S-4)
        OndaLog.info("reset 완료 — 새 anon_id 발급")
    }

    fun setUserAttributes(attrs: Map<String, Any?>) = work.execute {
        val ext = identity.externalId
        if (ext == null) { OndaLog.warn("setUserAttributes: identify 이전 호출 — 무시"); return@execute }
        network.sendIdentify(ext, identity.anonId, attrs) { }
    }

    fun track(name: String, properties: Map<String, Any?>) {
        val item = EventQueue.Item(
            insertId = UUID.randomUUID().toString(),
            event = name,
            properties = properties,
            clientTs = iso8601(),
            anonId = identity.anonId,
            externalId = identity.externalId,
        )
        queue.enqueue(item)
        if (queue.count >= config.flushBatchSize) flush()
    }

    fun flush() = work.execute { flushSync() }

    // MARK: 푸시

    fun setPushSubscription(optedIn: Boolean) = work.execute { push.setServiceOptIn(optedIn) }

    fun getPushSubscription(osPermission: String) = push.subscriptionState(osPermission)

    /** FCM onNewToken 또는 위임 API에서 획득한 토큰을 대사. */
    fun onPushToken(token: String, osPermission: String) = work.execute {
        push.registerToken(token, identity.externalId, identity.anonId, osPermission)
    }

    /**
     * 원격 메시지 처리 (기본 FMS·위임 API 공통 진입). Onda 메시지면 true.
     * @param opened true=탭 진입(딥링크 라우팅), false=수신
     */
    fun handleRemoteMessage(data: Map<String, String>, opened: Boolean): Boolean {
        val payload = PushPayload.parse(data) ?: return false
        if (opened) {
            track("\$push_opened", pushProps(payload))
            bus.emitOpened(payload)
        } else {
            track("\$push_received", pushProps(payload))
            bus.emitReceived(payload)
        }
        return true
    }

    private fun pushProps(p: PushPayload): Map<String, Any?> = buildMap {
        put("message_id", p.messageId)
        p.campaignId?.let { put("campaign_id", it) }
        p.journeyId?.let { put("journey_id", it) }
    }

    // MARK: 내부

    private fun flushSync() {
        if (flushing) return
        val batch = queue.peek(config.flushBatchSize)
        if (batch.isEmpty()) return
        flushing = true
        val ids = batch.map { it.insertId }.toSet()
        network.sendTrack(batch) { ok ->
            work.execute {
                if (ok) queue.ack(ids)
                flushing = false
            }
        }
    }

    private fun iso8601(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
}
