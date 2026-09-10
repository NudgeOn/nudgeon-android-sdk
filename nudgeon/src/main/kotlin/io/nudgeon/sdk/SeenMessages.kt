package io.nudgeon.sdk

/**
 * 같은 message_id의 수신을 한 번만 앱에 전달한다 (iOS SeenMessages와 대칭).
 *
 * 서버 채널 워커는 at-least-once다 — 공급자 전송이 끝난 직후 죽으면 같은 message_id가 한 번 더 온다
 * (플랫폼 M-4 카오스 2026-09-10, 3,000건 중 1건). FCM에는 APNs collapse-id 같은 접기 수단이 없어
 * (collapse_key는 기기당 4개 제한) 단말에서 접는다. 최근 [capacity]개를 순서대로 기억한다.
 *
 * Android 프레임워크에 의존하지 않아 JVM 단위 테스트 대상이다.
 */
internal class SeenMessages(private val store: Store, private val capacity: Int = 256) {
    /** 최근 본 message_id 목록 영속 — SharedPreferences 또는 테스트용 메모리. 오래된 것이 앞. */
    interface Store {
        var ids: List<String>
    }

    /** 처음 보는 message_id면 기억하고 true, 이미 봤으면 false. */
    fun firstTime(messageId: String): Boolean {
        if (messageId.isEmpty()) return true
        val ids = store.ids
        if (messageId in ids) return false
        val next = if (ids.size >= capacity) ids.drop(ids.size - capacity + 1) else ids
        store.ids = next + messageId
        return true
    }
}
