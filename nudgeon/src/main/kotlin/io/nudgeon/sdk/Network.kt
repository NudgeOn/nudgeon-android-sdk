package io.nudgeon.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 배치 업로드 클라이언트 (PRD-01 6.1). 재시도는 큐 잔존으로 처리. iOS Network와 대칭.
 * 의존 최소화를 위해 HttpURLConnection 사용(OkHttp 미도입).
 */
internal class Network(
    private val config: NudgeOnConfig,
    private val deviceId: String,
    private val io: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    /** track 배치 전송. 성공(2xx) 시 true → 호출자가 큐에서 ack. */
    fun sendTrack(items: List<EventQueue.Item>, completion: (Boolean) -> Unit) {
        if (items.isEmpty()) { completion(true); return }
        val batch = JSONArray()
        for (it in items) {
            val e = JSONObject().apply {
                put("insert_id", it.insertId)
                put("anon_id", it.anonId)
                put("event", it.event)
                put("client_ts", it.clientTs)
                put("properties", JSONObject(it.properties))
                it.externalId?.let { ext -> put("external_id", ext) }
            }
            batch.put(e)
        }
        val body = JSONObject().apply {
            put("batch", batch)
            put("device", JSONObject().put("device_id", deviceId).put("platform", "android"))
        }
        post("/v1/track", body, completion)
    }

    fun sendIdentify(externalId: String, anonId: String, attributes: Map<String, Any?>, completion: (Boolean) -> Unit) {
        val body = JSONObject().apply {
            put("external_id", externalId)
            put("anon_id", anonId)
            put("attributes", JSONObject(attributes))
        }
        post("/v1/identify", body, completion)
    }

    fun registerToken(pushToken: String, externalId: String?, anonId: String, osPermission: String, completion: (Boolean) -> Unit) {
        val body = JSONObject().apply {
            put("device", JSONObject().put("device_id", deviceId).put("platform", "android"))
            put("push_token", pushToken)
            put("os_permission", osPermission)
            put("anon_id", anonId)
            externalId?.let { put("external_id", it) }
        }
        post("/v1/devices/token", body, completion)
    }

    private fun post(path: String, body: JSONObject, completion: (Boolean) -> Unit) {
        io.execute {
            val ok = runCatching {
                val url = URL(URL(config.apiHost), path)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer ${config.sdkKey}")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                code in 200..299
            }.getOrDefault(false)
            completion(ok)
        }
    }
}
