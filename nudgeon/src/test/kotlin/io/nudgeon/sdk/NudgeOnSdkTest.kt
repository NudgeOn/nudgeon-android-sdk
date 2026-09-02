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
                "data" to """{"k":"v","n":3}""",
            ),
        )
        assertEquals("m-1", p?.messageId)
        assertEquals("c-1", p?.campaignId)
        assertEquals("제목", p?.title)
        assertEquals("myapp://x", p?.deepLink)
        assertEquals("v", p?.data?.get("k"))
        assertEquals("3", p?.data?.get("n")) // 숫자도 문자열 평탄화
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
