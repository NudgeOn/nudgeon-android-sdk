package io.nudgeon.sdk

/**
 * POST_NOTIFICATIONS 요청의 미완료 콜백을 들고 있다가, OS 응답이 도착한 시점에 한 번만 완료한다.
 *
 * 배경(M-1 실단말, 2026-09-07): `registerForPush`가 `requestPermissions()` 직후 현재 상태(아직 denied)를
 * 콜백하고 끝나서, 사용자가 다이얼로그에서 허용해도 서버 os_permission이 갱신되지 않았다.
 * 앱이 `setPushToken`을 다시 불러야 했다.
 *
 * 완료 경로는 둘이고 먼저 온 쪽이 이긴다:
 *  1. 앱이 `NudgeOn.onRequestPermissionsResult`를 전달 (권장 — 다이얼로그 없이 즉시 거부되는 경우도 잡는다).
 *  2. 요청한 Activity가 시스템 다이얼로그에 가려져 pause됐다가 다시 resume됨 (앱 코드 변경 없이 동작).
 *     onCreate/onStart에서 요청하면 다이얼로그가 뜨기 전에 첫 onResume이 오므로, pause를 거치지 않은
 *     resume은 무시한다. 다이얼로그 없이 즉시 거부되는 "다시 묻지 않음" 상태는 pause가 없어 1번 경로나
 *     다음 begin()(콜백 연쇄)에서 완료된다.
 *
 * Android 프레임워크에 의존하지 않아 JVM 단위 테스트 대상이다. 동기화는 호출부(NudgeOn object)가 맡는다.
 */
internal class PermissionRequestTracker {
    private var pending: ((PushPermissionResult) -> Unit)? = null
    private var requester: Int = 0 // 요청한 Activity의 identityHashCode — 다른 Activity resume은 무시
    private var pausedSinceRequest = false

    val hasPending: Boolean get() = pending != null

    /** 요청 시작. 이미 대기 중인 콜백이 있으면 그 콜백은 새 요청과 함께 완료된다(유실 없음). */
    fun begin(requesterId: Int, callback: (PushPermissionResult) -> Unit) {
        val prev = pending
        requester = requesterId
        pausedSinceRequest = false
        pending = if (prev == null) callback else { r -> prev(r); callback(r) }
    }

    /** Activity pause 신호 — 요청한 Activity가 다이얼로그에 가려졌다. */
    fun onPaused(activityId: Int) {
        if (pending != null && activityId == requester) pausedSinceRequest = true
    }

    /**
     * Activity resume 신호. 요청한 Activity가 pause를 거친 뒤의 resume만 완료로 본다.
     * @return 완료할 콜백(호출부가 현재 권한을 계산해 실행). 대기 중이 아니면 null.
     */
    fun onResumed(activityId: Int): ((PushPermissionResult) -> Unit)? {
        if (pending == null || activityId != requester || !pausedSinceRequest) return null
        return take()
    }

    /** onRequestPermissionsResult 전달 신호. 대기 중이 아니면 null. */
    fun onResult(): ((PushPermissionResult) -> Unit)? = if (pending == null) null else take()

    private fun take(): ((PushPermissionResult) -> Unit)? {
        val cb = pending
        pending = null
        requester = 0
        pausedSinceRequest = false
        return cb
    }
}
