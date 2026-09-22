package io.nudgeon.sdk

import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StandardAttributesTest {
    @Test fun standardAttributesUseIdentifyTransport() {
        val attrs = mapOf<String, Any?>(
            NudgeOnAttributes.FIRST_NAME to "Minji",
            NudgeOnAttributes.LAST_NAME to "Kim",
            NudgeOnAttributes.EMAIL to "minji@example.com",
            NudgeOnAttributes.PHONE to "+821012345678",
            NudgeOnAttributes.DOB to "1995-03-15",
            NudgeOnAttributes.GENDER to "F",
            NudgeOnAttributes.HOME_CITY to "Seoul",
            NudgeOnAttributes.COUNTRY to "KR",
            NudgeOnAttributes.LANGUAGE to "ko",
            NudgeOnAttributes.TIMEZONE to "Asia/Seoul",
            NudgeOnAttributes.CREATED_AT to "2026-09-22T00:00:00Z",
            "score" to 0, "enabled" to false, "removed" to null
        )
        val expected = JSONObject("""{"first_name": "Minji", "last_name": "Kim", "email": "minji@example.com", "phone": "+821012345678", "dob": "1995-03-15", "gender": "F", "home_city": "Seoul", "country": "KR", "language": "ko", "timezone": "Asia/Seoul", "created_at": "2026-09-22T00:00:00Z", "score": 0, "enabled": false, "removed": null}""")
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
                    check(input.readLine().startsWith("POST /v1/identify "))
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
            val config = NudgeOnConfig(sdkKey = "test", apiHost = "http://127.0.0.1:${server.localPort}")
            Network(config, UUID.randomUUID().toString(), io).sendIdentify("profile-123", UUID.randomUUID().toString(), attrs) {
                accepted = it
                done.countDown()
            }
            assertTrue("track request completed", done.await(10, TimeUnit.SECONDS))
            assertTrue(accepted)
            val body = received.get(10, TimeUnit.SECONDS)
            assertEquals("profile-123", body.getString("external_id"))
            val actual = body.getJSONObject("attributes")
            assertEquals(expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
            expected.keys().forEach { key -> assertEquals(key, expected.get(key), actual.get(key)) }
        } finally {
            io.shutdownNow()
            server.close()
            serverExecutor.shutdownNow()
        }
    }
}
