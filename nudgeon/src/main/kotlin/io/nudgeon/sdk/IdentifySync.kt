package io.nudgeon.sdk

/**
 * identify 재시도 (iOS NudgeOnCore.sendPendingIdentify와 대칭).
 *
 * 배경(M-1 실단말, 2026-09-07): identify가 네트워크 오류로 실패하면 로그만 남기고 유실돼 서버가 대신
 * identify해야 했다. anon 이벤트가 그 유저에 묶이는 것은 이 호출이 서버에 닿아야만 일어난다.
 *
 * 서버 반영 전에는 (external_id, 당시 anon_id)를 [Store]에 영속해 두고, 실패하면 다음 flush(타이머·
 * 포그라운드·앱 재시작)에서 같은 쌍으로 재전송한다. 성공 시 같은 쌍일 때만 지운다 — 전송 중 다른
 * 유저로 identify가 들어오면 그쪽이 남아 이어서 전송된다. 전송 중 중복 없음.
 *
 * Android 프레임워크에 의존하지 않아 JVM 단위 테스트 대상이다. 호출은 코어의 단일 워커 스레드에서만 한다.
 */
internal class IdentifySync(
    private val store: Store,
    private val send: (externalId: String, anonId: String, done: (Boolean) -> Unit) -> Unit,
    private val onWorker: (Runnable) -> Unit,
) {
    /** pending 마커 영속 — SharedPreferences 또는 테스트용 메모리. */
    interface Store {
        var pending: Pair<String, String>? // (external_id, anon_id)
    }

    private var inflight = false

    val pending: Pair<String, String>? get() = store.pending

    /** identify 호출 — 마커를 먼저 남기고 전송한다. */
    fun identify(externalId: String, anonId: String) {
        store.pending = externalId to anonId
        sendPending()
    }

    /** 대기 중 identify를 전송한다. 전송 중이면 겹치지 않는다. flush마다 호출된다. */
    fun sendPending() {
        if (inflight) return
        val (ext, anon) = store.pending ?: return
        inflight = true
        send(ext, anon) { ok ->
            onWorker(
                Runnable {
                    inflight = false
                    if (ok) {
                        if (store.pending == ext to anon) store.pending = null
                        NudgeOnLog.info("identify 성공")
                        if (store.pending != null) sendPending() // 전송 중 바뀐 유저
                    } else {
                        NudgeOnLog.warn("identify 실패 — 다음 flush에서 재시도")
                    }
                },
            )
        }
    }

    /** reset — 이전 유저의 미전송 identify를 버린다(코어가 직전에 마지막 1회 전송을 시도한다). */
    fun drop() { store.pending = null }
}
