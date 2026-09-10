package io.nudgeon.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * 프레임워크 비의존(pure JVM) 단위 테스트 — iOS 스위트와 대칭.
 * Identity·PushManager(SharedPreferences 의존)는 Robolectric 스위트에서 별도 검증.
 */
class PushPayloadTest {
    @Test fun parsesNudgeOnMessage() {
        val p = PushPayload.parse(
            mapOf(
                "message_id" to "m-1", "campaign_id" to "c-1", "journey_id" to "j-1",
                "title" to "제목", "body" to "본문", "deep_link" to "myapp://x",
                "image_url" to "https://x/i.png",
                "data" to """{"k":"v","n":3}""",
            ),
        )
        assertEquals("m-1", p?.messageId)
        assertEquals("j-1", p?.journeyId)
        assertEquals("https://x/i.png", p?.imageUrl)
        assertEquals("c-1", p?.campaignId)
        assertEquals("제목", p?.title)
        assertEquals("myapp://x", p?.deepLink)
        assertEquals("v", p?.data?.get("k"))
        assertEquals("3", p?.data?.get("n")) // 숫자도 문자열 평탄화
    }

    @Test fun imageUrlAbsentIsNull() {
        assertNull(PushPayload.parse(mapOf("message_id" to "m-1", "title" to "t"))?.imageUrl)
    }

    @Test fun returnsNullForNonNudgeOnMessage() {
        assertNull(PushPayload.parse(mapOf("title" to "hi"))) // message_id 없음 → 공존
    }

    @Test fun parsesSilentFlag() {
        val silent = PushPayload.parse(mapOf("message_id" to "m-1", "silent" to "1"))
        assertEquals(true, silent?.silent) // 무음 푸시 마커
        val normal = PushPayload.parse(mapOf("message_id" to "m-2", "title" to "t"))
        assertEquals(false, normal?.silent) // 일반 푸시는 silent=false
    }
}

class EventBusTest {
    private fun bus() = EventBus { it() } // 동기 전달
    private fun payload(id: String) = PushPayload(id, null, null, "t", "b", null, emptyMap())

    @Test fun coldStartBufferReplayedOnFirstSubscribe() {
        val bus = bus()
        bus.emitOpened(payload("m1")) // 리스너 등록 전 (콜드 스타트)
        val got = mutableListOf<String>()
        bus.onPushOpened { got.add(it.messageId) }
        assertEquals(listOf("m1"), got)
    }

    @Test fun initialPushPayloadWithoutListener() {
        val bus = bus()
        bus.emitOpened(payload("m9"))
        assertEquals("m9", bus.getInitialPushPayload()?.messageId)
    }

    @Test fun bufferCappedAt20() {
        val bus = bus()
        repeat(25) { bus.emitReceived(payload("m$it")) }
        var count = 0
        bus.onPushReceived { count++ }
        assertEquals(20, count) // oldest drop
    }

    @Test fun offStopsDelivery() {
        val bus = bus()
        var count = 0
        val token = bus.onPushReceived { count++ }
        bus.off(token)
        bus.emitReceived(payload("x"))
        assertEquals(0, count)
    }
}

class EventQueueTest {
    private fun tempFile() = File.createTempFile("nudgeon_${UUID.randomUUID()}", ".json").apply { deleteOnExit() }

    @Test fun enqueuePeekAck() {
        val q = EventQueue(tempFile())
        q.enqueue(EventQueue.Item("i1", "e", emptyMap(), "2026-08-30T00:00:00Z", "a", null))
        assertEquals(1, q.count)
        assertEquals("i1", q.peek(10).first().insertId)
        q.ack(setOf("i1"))
        assertEquals(0, q.count)
    }

    @Test fun durabilityAcrossReload() {
        val f = tempFile()
        EventQueue(f).enqueue(EventQueue.Item("i2", "e", mapOf("k" to "v"), "t", "a", "ext"))
        val reloaded = EventQueue(f) // 앱 킬 후 재기동 시뮬레이션
        assertEquals(1, reloaded.count)
        assertEquals("ext", reloaded.peek(1).first().externalId)
    }

    @Test fun oldestDropOverCap() {
        val q = EventQueue(tempFile())
        repeat(1005) { q.enqueue(EventQueue.Item("i$it", "e", emptyMap(), "t", "a", null)) }
        assertEquals(1000, q.count)
        assertEquals("i5", q.peek(1).first().insertId) // 오래된 5건 drop
    }
}

/**
 * registerForPush 권한 응답 대기 (M-1 실단말 결함: 요청 직후 denied를 콜백하고 끝나 서버 권한이 안 바뀜).
 * Android 프레임워크 없이 상태기계만 검증한다.
 */
class PermissionRequestTrackerTest {
    private val activity = 1001
    private val other = 2002

    @Test fun completesOnResumeAfterPause() {
        val t = PermissionRequestTracker()
        val got = mutableListOf<PushPermissionResult>()
        t.begin(activity) { got.add(it) }
        assertNull(t.onResumed(activity)) // onCreate에서 요청 → 다이얼로그 전 첫 resume은 무시
        t.onPaused(activity) // 시스템 다이얼로그가 가림
        val cb = t.onResumed(activity)
        assertTrue(cb != null)
        cb!!(PushPermissionResult.GRANTED)
        assertEquals(listOf(PushPermissionResult.GRANTED), got)
        assertFalse(t.hasPending)
        assertNull(t.onResumed(activity)) // 두 번 완료되지 않는다
    }

    @Test fun ignoresOtherActivities() {
        val t = PermissionRequestTracker()
        t.begin(activity) {}
        t.onPaused(other)
        assertNull(t.onResumed(other))
        assertTrue(t.hasPending)
        t.onPaused(activity)
        assertTrue(t.onResumed(activity) != null)
    }

    @Test fun forwardedResultWinsAndClearsResumePath() {
        val t = PermissionRequestTracker()
        var calls = 0
        t.begin(activity) { calls++ }
        t.onPaused(activity)
        t.onResult()!!(PushPermissionResult.DENIED)
        assertNull(t.onResumed(activity)) // resume 경로는 더 이상 콜백하지 않는다
        assertEquals(1, calls)
        assertNull(t.onResult()) // 대기 없음
    }

    @Test fun repeatedBeginChainsPendingCallback() {
        val t = PermissionRequestTracker()
        val got = mutableListOf<String>()
        t.begin(activity) { got.add("first:$it") }
        t.begin(activity) { got.add("second:$it") } // 앞 요청이 응답 없이 끝난 경우(다이얼로그 없이 거부)
        t.onPaused(activity)
        t.onResumed(activity)!!(PushPermissionResult.DENIED)
        assertEquals(listOf("first:DENIED", "second:DENIED"), got)
    }
}

/** identify 재시도 (M-1 실단말 결함: 실패가 로그만 남기고 유실). iOS IdentifyRetryTests와 대칭. */
class IdentifySyncTest {
    private class MemStore : IdentifySync.Store { override var pending: Pair<String, String>? = null }
    private class FakeSender {
        val sent = mutableListOf<Pair<String, String>>()
        val statuses = ArrayDeque<Boolean>() // 순서대로 소비, 비면 성공
        var held: ((Boolean) -> Unit)? = null // hold=true면 완료를 보류
        var hold = false
        fun send(ext: String, anon: String, done: (Boolean) -> Unit) {
            sent.add(ext to anon)
            if (hold) held = done else done(statuses.removeFirstOrNull() ?: true)
        }
    }
    private fun make(store: MemStore = MemStore()): Triple<IdentifySync, FakeSender, MemStore> {
        val sender = FakeSender()
        return Triple(IdentifySync(store, sender::send) { it.run() }, sender, store)
    }

    @Test fun failedIdentifyRetriedOnNextFlushWithSameAnon() {
        val (sync, sender, store) = make()
        sender.statuses.add(false)
        sync.identify("user-1", "anon-a")
        assertEquals("user-1" to "anon-a", store.pending) // 실패분은 pending으로 남는다
        sync.sendPending() // 다음 flush
        assertEquals(listOf("user-1" to "anon-a", "user-1" to "anon-a"), sender.sent)
        assertNull(store.pending)
        sync.sendPending() // 성공 후에는 보내지 않는다
        assertEquals(2, sender.sent.size)
    }

    @Test fun pendingSurvivesRestart() {
        val store = MemStore()
        val (first, s1, _) = make(store)
        s1.statuses.add(false)
        first.identify("user-2", "anon-b")
        val (restarted, s2, _) = make(store) // 같은 저장소로 새 코어
        restarted.sendPending() // start()의 첫 flush
        assertEquals(listOf("user-2" to "anon-b"), s2.sent)
        assertNull(store.pending)
    }

    @Test fun inflightIsNotDuplicatedAndNewerUserFollows() {
        val (sync, sender, store) = make()
        sender.hold = true
        sync.identify("user-a", "anon-c")
        sync.sendPending() // 전송 중 flush — 중복 없음
        assertEquals(1, sender.sent.size)
        sync.identify("user-b", "anon-c") // 전송 중 유저 변경 — 마커만 교체
        assertEquals(1, sender.sent.size)
        sender.hold = false
        sender.held!!(true) // 첫 전송 성공 → 마커가 다르므로 지우지 않고 이어서 전송
        assertEquals(listOf("user-a" to "anon-c", "user-b" to "anon-c"), sender.sent)
        assertNull(store.pending)
    }

    @Test fun dropDiscardsPendingAfterReset() {
        val (sync, sender, store) = make()
        sender.statuses.add(false)
        sync.identify("user-3", "anon-d")
        sync.drop()
        sync.sendPending()
        assertNull(store.pending)
        assertEquals(1, sender.sent.size)
    }
}

/** 같은 message_id 재수신 접기 (플랫폼 M-4: 서버 at-least-once 창에서 같은 메시지가 한 번 더 온다). */
class SeenMessagesTest {
    private class MemStore : SeenMessages.Store { override var ids: List<String> = emptyList() }

    @Test fun secondDeliveryOfSameMessageIsNotFirstTime() {
        val seen = SeenMessages(MemStore())
        assertTrue(seen.firstTime("m-1"))
        assertFalse(seen.firstTime("m-1"))
        assertTrue(seen.firstTime("m-2"))
    }

    @Test fun rememberedAcrossInstancesViaStore() {
        val store = MemStore()
        assertTrue(SeenMessages(store).firstTime("m-1"))
        assertFalse(SeenMessages(store).firstTime("m-1")) // 프로세스 재시작 후에도 접는다
    }

    @Test fun capacityEvictsOldest() {
        val store = MemStore()
        val seen = SeenMessages(store, capacity = 3)
        for (i in 1..4) seen.firstTime("m-$i")
        assertEquals(listOf("m-2", "m-3", "m-4"), store.ids)
        assertTrue(seen.firstTime("m-1")) // 밀려난 것은 다시 처음으로 본다
    }

    @Test fun emptyIdNeverDeduped() {
        val seen = SeenMessages(MemStore())
        assertTrue(seen.firstTime(""))
        assertTrue(seen.firstTime(""))
    }
}
