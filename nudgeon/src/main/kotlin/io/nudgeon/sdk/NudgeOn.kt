package io.nudgeon.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.UUID

/**
 * NudgeOn Android SDK 공개 진입점 (PRD-01A 2장). iOS와 API 완전 동형.
 * 코어가 유일한 상태 보유자: 오프라인 큐·식별자 영속·배치 플러시·토큰 라이프사이클.
 */
object NudgeOn {
    @Volatile
    private var core: NudgeOnCore? = null
    private var appContext: Context? = null
    private val permissionRequest = PermissionRequestTracker() // synchronized(this)로 보호

    /** 초기화 (PRD-01A 2.1). initialize 이전 호출은 코어 내부 큐에 보관 후 순서 실행. */
    @JvmStatic
    fun initialize(context: Context, config: NudgeOnConfig) {
        synchronized(this) {
            if (core != null) { NudgeOnLog.warn("이미 초기화됨 — 중복 initialize 무시"); return }
            appContext = context.applicationContext
            core = NudgeOnCore(context.applicationContext, config).also { it.start() }
            observeForeground()
            observeActivityResume()
        }
    }

    /**
     * 앱 포그라운드 복귀(ProcessLifecycle ON_START)마다 OS 알림 권한 변경을 서버에 재동기화 (R-08).
     * 사용자가 시스템 설정에서 알림을 끄면 FCM 토큰 값은 그대로라 자동 갱신 계기가 없다 —
     * 포그라운드마다 현재 권한을 계산해 캐시된 토큰으로 재등록(권한 대사 포함이라 변경 시에만 서버 호출).
     */
    private fun observeForeground() {
        val ctx = appContext ?: return
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                core?.resyncPushPermission(osPermissionString(ctx))
            }
        })
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
     * 알림 권한 확인/요청 (API 33+ POST_NOTIFICATIONS). 이미 허용이면 GRANTED 즉시 콜백.
     * 아니면 activity로 권한을 요청하고 **사용자 응답이 도착한 뒤** 최종 상태를 콜백한다 — 동시에
     * 서버의 os_permission을 재동기화한다(등록된 토큰이 있을 때). 응답 감지는 두 경로 중 먼저 온 쪽:
     *  - 앱이 [onRequestPermissionsResult]를 전달 (권장)
     *  - 요청한 Activity가 시스템 다이얼로그에 가려졌다가(pause) 다시 resume됨 (앱 코드 변경 없이 동작)
     * API 33 미만 또는 activity가 null이면 요청 없이 현재 상태를 즉시 콜백한다.
     */
    @JvmStatic
    fun registerForPush(activity: Activity?, callback: (PushPermissionResult) -> Unit) {
        val ctx = appContext ?: run { callback(PushPermissionResult.DENIED); return }
        if (osPermissionGranted(ctx)) { callback(PushPermissionResult.GRANTED); return }
        if (Build.VERSION.SDK_INT >= 33 && activity != null) {
            synchronized(this) { permissionRequest.begin(System.identityHashCode(activity), callback) }
            activity.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_PUSH)
            return
        }
        callback(PushPermissionResult.DENIED)
    }

    /**
     * Activity.onRequestPermissionsResult에서 전달하면 [registerForPush] 콜백을 즉시 완료하고 서버 권한을
     * 재동기화한다. NudgeOn의 요청이 아니면 false를 돌려주고 아무것도 하지 않는다.
     * 전달하지 않아도 Activity resume 시점에 같은 처리가 일어난다(다이얼로그 없이 즉시 거부되는
     * "다시 묻지 않음" 상태만 resume 신호가 없어 다음 resume까지 늦어진다).
     */
    @JvmStatic
    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode != REQ_PUSH) return false
        val cb = synchronized(this) { permissionRequest.onResult() } ?: return true
        completePermissionRequest(cb)
        return true
    }

    /** 대기 중 요청 완료: 현재 OS 권한을 계산해 서버 재동기화 + 콜백. */
    private fun completePermissionRequest(callback: (PushPermissionResult) -> Unit) {
        val ctx = appContext ?: run { callback(PushPermissionResult.DENIED); return }
        val granted = osPermissionGranted(ctx)
        core?.resyncPushPermission(if (granted) "authorized" else "denied")
        callback(if (granted) PushPermissionResult.GRANTED else PushPermissionResult.DENIED)
    }

    /** 요청한 Activity가 시스템 권한 다이얼로그 뒤에 resume되면 대기 중인 요청을 완료한다. */
    private fun observeActivityResume() {
        val app = appContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val cb = synchronized(this@NudgeOn) {
                    permissionRequest.onResumed(System.identityHashCode(activity))
                } ?: return
                completePermissionRequest(cb)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {
                synchronized(this@NudgeOn) { permissionRequest.onPaused(System.identityHashCode(activity)) }
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** FCM 토큰 등록 진입점 (NudgeOnFirebaseMessagingService.onNewToken 또는 앱이 직접 호출). */
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
     * 타사 FMS 공존 위임 API (PRD-01A 3.2). NudgeOn 메시지면 true 반환 후 처리.
     * 자체 FirebaseMessagingService를 쓰는 앱은 onMessageReceived에서 이 함수로 위임한다.
     */
    @JvmStatic
    fun handleRemoteMessage(data: Map<String, String>): Boolean =
        core?.handleRemoteMessage(data, opened = false) ?: false

    /** 푸시 탭으로 앱 진입 시 호출 (딥링크 라우팅). NudgeOn 메시지면 true. */
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

internal object NudgeOnLog {
    fun warn(msg: String) { android.util.Log.w("NudgeOn", msg) }
    fun info(msg: String) { android.util.Log.i("NudgeOn", msg) }
}
