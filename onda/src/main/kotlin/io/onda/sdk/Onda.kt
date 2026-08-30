package io.onda.sdk

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import java.util.UUID

/**
 * Onda Android SDK 공개 진입점 (PRD-01A 2장). iOS와 API 완전 동형.
 * 코어가 유일한 상태 보유자: 오프라인 큐·식별자 영속·배치 플러시·토큰 라이프사이클.
 */
object Onda {
    @Volatile
    private var core: OndaCore? = null
    private var appContext: Context? = null

    /** 초기화 (PRD-01A 2.1). initialize 이전 호출은 코어 내부 큐에 보관 후 순서 실행. */
    @JvmStatic
    fun initialize(context: Context, config: OndaConfig) {
        synchronized(this) {
            if (core != null) { OndaLog.warn("이미 초기화됨 — 중복 initialize 무시"); return }
            appContext = context.applicationContext
            core = OndaCore(context.applicationContext, config).also { it.start() }
        }
    }

    @JvmStatic fun identify(externalId: String) { core?.identify(externalId) }
    @JvmStatic fun reset() { core?.reset() }
    @JvmStatic fun setUserAttributes(attrs: Map<String, Any?>) { core?.setUserAttributes(attrs) }
    @JvmStatic fun track(name: String, properties: Map<String, Any?> = emptyMap()) { core?.track(name, properties) }
    @JvmStatic fun flush() { core?.flush() }

    @JvmStatic fun getDeviceId(): String? = core?.deviceId
    @JvmStatic fun getAnonId(): String? = core?.anonId

    // MARK: 푸시 (PRD-01A 2.4, 3.2)

    /**
     * 알림 권한 확인/요청 (API 33+ POST_NOTIFICATIONS). 이미 허용이면 GRANTED 즉시 콜백,
     * 아니면 activity로 권한 요청 후 현재 상태를 콜백(결과는 앱의 onRequestPermissionsResult에서 재확인).
     */
    @JvmStatic
    fun registerForPush(activity: Activity?, callback: (PushPermissionResult) -> Unit) {
        val ctx = appContext ?: run { callback(PushPermissionResult.DENIED); return }
        if (osPermissionGranted(ctx)) { callback(PushPermissionResult.GRANTED); return }
        if (Build.VERSION.SDK_INT >= 33 && activity != null) {
            activity.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_PUSH)
        }
        callback(if (osPermissionGranted(ctx)) PushPermissionResult.GRANTED else PushPermissionResult.DENIED)
    }

    /** FCM 토큰 등록 진입점 (OndaFirebaseMessagingService.onNewToken 또는 앱이 직접 호출). */
    @JvmStatic
    fun setPushToken(token: String) {
        val ctx = appContext ?: return
        core?.onPushToken(token, osPermissionString(ctx))
    }

    @JvmStatic fun setPushSubscription(optedIn: Boolean) { core?.setPushSubscription(optedIn) }

    @JvmStatic
    fun getPushSubscription(): SubscriptionState {
        val ctx = appContext
        val perm = if (ctx != null) osPermissionString(ctx) else "not_determined"
        return core?.getPushSubscription(perm)
            ?: SubscriptionState(serviceOptIn = true, osPermission = perm, tokenRegistered = false)
    }

    /**
     * 타사 FMS 공존 위임 API (PRD-01A 3.2). Onda 메시지면 true 반환 후 처리.
     * 자체 FirebaseMessagingService를 쓰는 앱은 onMessageReceived에서 이 함수로 위임한다.
     */
    @JvmStatic
    fun handleRemoteMessage(data: Map<String, String>): Boolean =
        core?.handleRemoteMessage(data, opened = false) ?: false

    /** 푸시 탭으로 앱 진입 시 호출 (딥링크 라우팅). Onda 메시지면 true. */
    @JvmStatic
    fun handlePushOpened(data: Map<String, String>): Boolean =
        core?.handleRemoteMessage(data, opened = true) ?: false

    // MARK: 리스너 (PRD-01A 2.5) — 콜드 스타트 유실 없이 전달

    @JvmStatic fun onPushOpened(handler: (PushPayload) -> Unit): UUID? = core?.bus?.onPushOpened(handler)
    @JvmStatic fun onPushReceived(handler: (PushPayload) -> Unit): UUID? = core?.bus?.onPushReceived(handler)
    @JvmStatic fun off(token: UUID) { core?.bus?.off(token) }

    /** 콜드 스타트로 앱이 푸시 탭에 의해 열렸으면 그 페이로드, 아니면 null. */
    @JvmStatic fun getInitialPushPayload(): PushPayload? = core?.bus?.getInitialPushPayload()

    // MARK: 내부

    private const val REQ_PUSH = 7011 // POST_NOTIFICATIONS 요청 코드

    private fun osPermissionGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
                PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    private fun osPermissionString(ctx: Context): String =
        if (osPermissionGranted(ctx)) "authorized" else "denied"
}

internal object OndaLog {
    fun warn(msg: String) { android.util.Log.w("Onda", msg) }
    fun info(msg: String) { android.util.Log.i("Onda", msg) }
}
