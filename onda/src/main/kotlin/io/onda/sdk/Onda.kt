package io.onda.sdk

import android.content.Context

/**
 * Onda Android SDK 공개 진입점 (PRD-01A 2장). iOS와 API 완전 동형.
 * 코어가 유일한 상태 보유자: 오프라인 큐·식별자 영속·배치 플러시·토큰 라이프사이클.
 *
 * 상태: M1 골격. 구현 로드맵은 README 참조.
 */
object Onda {
    @Volatile
    private var core: OndaCore? = null

    /** 초기화 (PRD-01A 2.1). initialize 이전 호출은 코어 내부 큐에 보관 후 순서 실행. */
    @JvmStatic
    fun initialize(context: Context, config: OndaConfig) {
        synchronized(this) {
            if (core != null) {
                OndaLog.warn("이미 초기화됨 — 중복 initialize 무시")
                return
            }
            core = OndaCore(context.applicationContext, config).also { it.start() }
        }
    }

    @JvmStatic fun identify(externalId: String) = core?.identify(externalId)
    @JvmStatic fun reset() = core?.reset()
    @JvmStatic fun setUserAttributes(attrs: Map<String, Any?>) = core?.setUserAttributes(attrs)
    @JvmStatic fun track(name: String, properties: Map<String, Any?> = emptyMap()) =
        core?.track(name, properties)
    @JvmStatic fun flush() = core?.flush()

    @JvmStatic fun getDeviceId(): String? = core?.deviceId
    @JvmStatic fun getAnonId(): String? = core?.anonId

    // 푸시(registerForPush·handleRemoteMessage 위임 API·리스너)는 M2 (PRD-01A 3.2)
}

/** SDK 설정 (PRD-01A 2.1) */
data class OndaConfig(
    val sdkKey: String,
    val apiHost: String, // 셀프호스팅 시 교체
    val flushIntervalSeconds: Long = 10,
    val flushBatchSize: Int = 10,
    val autoTrackSessions: Boolean = true,
    val autoRegisterPushToken: Boolean = true,
)

internal object OndaLog {
    fun warn(msg: String) = android.util.Log.w("Onda", msg)
    fun info(msg: String) = android.util.Log.i("Onda", msg)
}
