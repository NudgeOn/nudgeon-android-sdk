package io.nudgeon.sdk

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StandardEventsTest {
    @Test fun standardNamesSurviveOfflineStorageAndTrackTransport() {
        val names = listOf(NudgeOnEvents.SIGN_UP, NudgeOnEvents.LOGIN,
            NudgeOnEvents.PURCHASE_COMPLETED, NudgeOnEvents.PRODUCT_VIEWED,
            NudgeOnEvents.ADD_TO_CART, NudgeOnEvents.CHECKOUT_STARTED, "purchase")
        val expected = listOf("sign_up", "login", "purchase_completed", "product_viewed",
            "add_to_cart", "checkout_started", "purchase")
        val file = File.createTempFile("nudgeon-standard-events", ".json")
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverExecutor = Executors.newSingleThreadExecutor()
        val io = Executors.newSingleThreadExecutor()
        val done = CountDownLatch(1)
        var accepted = false
        try {
            val received = serverExecutor.submit<JSONObject> {
                server.accept().use { socket ->
                    socket.soTimeout = 10000
                    val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    check(input.readLine().startsWith("POST /v1/track "))
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: error("Missing HTTP headers")
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            length = line.substringAfter(':').trim().toInt()
                        }
                    }
                    check(length in 1..65536)
                    val chars = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(chars, offset, length - offset)
                        check(count > 0)
                        offset += count
                    }
                    val body = JSONObject(String(chars))
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 202 Accepted\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                        flush()
                    }
                    body
                }
            }
            val queue = EventQueue(file)
            names.forEach { name -> queue.enqueue(EventQueue.Item(UUID.randomUUID().toString(), name,
                mapOf("order_id" to "order-123", "total_amount" to 29000, "currency" to "KRW", "item_count" to 1),
                "2026-09-21T00:00:00Z", UUID.randomUUID().toString(), "user-123")) }
            val restored = EventQueue(file).peek(100)
            val config = NudgeOnConfig(sdkKey = "test", apiHost = "http://127.0.0.1:${server.localPort}")
            Network(config, UUID.randomUUID().toString(), io).sendTrack(restored) {
                accepted = it
                done.countDown()
            }
            assertTrue("track request completed", done.await(10, TimeUnit.SECONDS))
            assertTrue(accepted)
            val batch = received.get(10, TimeUnit.SECONDS).getJSONArray("batch")
            assertEquals(expected.size, batch.length())
            expected.forEachIndexed { index, name ->
                val event = batch.getJSONObject(index)
                assertEquals(name, event.getString("event"))
                assertEquals(29000, event.getJSONObject("properties").getInt("total_amount"))
                assertEquals("KRW", event.getJSONObject("properties").getString("currency"))
            }
        } finally {
            io.shutdownNow()
            server.close()
            serverExecutor.shutdownNow()
            file.delete()
        }
    }
}
