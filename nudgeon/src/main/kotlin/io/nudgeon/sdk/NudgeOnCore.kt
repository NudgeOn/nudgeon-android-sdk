package io.nudgeon.sdk

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
 * 코어 오케스트레이터 — 식별자·큐·네트워크·플러시·푸시를 조율. iOS NudgeOnCore와 대칭.
 * 모든 상태 변경은 단일 워커 스레드에서 수행(공개 API 논블로킹).
 */
internal class NudgeOnCore(
    context: Context,
    private val config: NudgeOnConfig,
) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("nudgeon", Context.MODE_PRIVATE)
    private val identity = Identity(prefs)
    private val queue = EventQueue(File(context.filesDir, "nudgeon_events.json"))
    private val network = Network(config, identity.deviceId)
    private val push = PushManager(network, prefs)
    val bus = EventBus { r -> android.os.Handler(android.os.Looper.getMainLooper()).post(r) }

    private val work = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var flushing = false
    private val seen = SeenMessages(identity.seenMessages)
    private val identifySync = IdentifySync(
        store = identity.pendingIdentify,
        send = { ext, anon, done -> network.sendIdentify(ext, anon, emptyMap(), done) },
        onWorker = { r -> work.execute(r) },
    )

    val anonId: String get() = identity.anonId
    val deviceId: String get() = identity.deviceId

    fun start() {
        NudgeOnLog.info("NudgeOn 초기화: host=${config.apiHost}")
        if (config.autoTrackSessions) track("session_start", emptyMap())
        scheduler.scheduleWithFixedDelay(
            { flushSync() }, config.flushIntervalSeconds, config.flushIntervalSeconds, TimeUnit.SECONDS,
        )
        flush() // 이전 세션 잔존분(이벤트·미전송 identify) 즉시 전송 시도
    }

    /**
     * 식별. 서버 반영 전에는 pending 마커를 영속해 두고, 실패하면 다음 flush(타이머·포그라운드·앱 재시작)에서
     * 같은 (external_id, anon_id)로 재전송한다 (IdentifySync).
     */
    fun identify(externalId: String) = work.execute {
        identity.externalId = externalId
        identifySync.identify(externalId, identity.anonId)
    }

    fun reset() = work.execute {
        flushSync() // 이전 유저 이벤트(와 미전송 identify)를 먼저 비운다 — 마지막 1회 시도
        identity.reset() // 이전 유저의 pending identify는 여기서 버린다(새 anon을 그 유저에 묶지 않는다)
        push.clearTokenCache() // 다음 토큰을 새 유저로 재등록 (S-4)
        NudgeOnLog.info("reset 완료 — 새 anon_id 발급")
    }

    fun setUserAttributes(attrs: Map<String, Any?>) = work.execute {
        val ext = identity.externalId
        if (ext == null) { NudgeOnLog.warn("setUserAttributes: identify 이전 호출 — 무시"); return@execute }
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
     * 포그라운드 복귀 시 OS 알림 권한 변경을 서버에 재동기화한다 (R-08).
     * 캐시된 토큰이 있으면 현재 권한으로 재등록 — 권한이 대사에 포함되므로 변경 시에만 서버 호출.
     * osPermission은 호출부(NudgeOn, Context 보유)가 계산해 전달.
     */
    fun resyncPushPermission(osPermission: String) = work.execute {
        val token = push.cachedToken ?: return@execute // 등록 이력 없으면 대상 아님
        push.registerToken(token, identity.externalId, identity.anonId, osPermission)
    }

    /**
     * 원격 메시지 처리 (기본 FMS·위임 API 공통 진입). NudgeOn 메시지면 true.
     * @param opened true=탭 진입(딥링크 라우팅), false=수신
     */
    fun handleRemoteMessage(data: Map<String, String>, opened: Boolean): Boolean {
        val payload = PushPayload.parse(data) ?: return false
        // 무음(백그라운드) 푸시: 앱 삭제 감지용 시스템 ping — 표시·수신 이벤트·리스너 통지 없이 소비.
        // NudgeOn가 처리했으므로 true 반환(호스트 FMS가 기본 알림을 띄우지 않도록).
        if (payload.silent) return true
        if (opened) {
            track("\$push_opened", pushProps(payload))
            bus.emitOpened(payload)
        } else {
            // 같은 message_id의 재수신(서버 at-least-once 창)은 여기서 접는다 — 이벤트도 리스너도 두 번 가지 않는다.
            if (!seen.firstTime(payload.messageId)) {
                NudgeOnLog.info("중복 수신 접음: message_id=${payload.messageId}")
                return true
            }
            track("\$push_received", pushProps(payload))
            bus.emitReceived(payload)
            // data-only라 OS가 알림을 만들지 않는다 — 계약상 SDK가 표시 (앱이 직접 그리면 config로 끈다).
            // 이미지 다운로드가 있어 워커 스레드에서 그린다(호출 스레드가 메인이어도 안전).
            if (config.autoDisplayNotifications) {
                work.execute {
                    runCatching { PushNotifications.show(appContext, config, payload, data) }
                        .onFailure { NudgeOnLog.warn("알림 표시 실패: ${it.message}") }
                }
            }
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
        identifySync.sendPending() // 실패했던 identify를 이벤트보다 먼저 — 귀속이 먼저 서버에 닿게
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
