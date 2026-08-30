package io.onda.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 오프라인 영속 이벤트 큐 (PRD-01 7.2). 앱 킬·오프라인에도 유실 없음. iOS EventQueue와 대칭.
 * MVP: 파일 기반 JSON 영속. 상한 1000건(초과 시 oldest drop). 내부 동기화로 접근 보호.
 */
internal class EventQueue(private val file: File) {

    data class Item(
        val insertId: String,
        val event: String,
        val properties: Map<String, Any?>,
        val clientTs: String,
        val anonId: String,
        val externalId: String?,
    )

    private val maxItems = 1000
    private val lock = Any()
    private val items: MutableList<Item> = load()

    fun enqueue(item: Item) = synchronized(lock) {
        items.add(item)
        if (items.size > maxItems) {
            repeat(items.size - maxItems) { items.removeAt(0) } // oldest drop
        }
        persist()
    }

    /** 최대 batchSize건을 꺼내 반환(제거하지 않음 — 전송 성공 후 ack로 제거). */
    fun peek(batchSize: Int): List<Item> = synchronized(lock) {
        items.take(batchSize)
    }

    /** 전송 성공한 insertId들을 제거. */
    fun ack(insertIds: Set<String>) = synchronized(lock) {
        items.removeAll { it.insertId in insertIds }
        persist()
    }

    val count: Int get() = synchronized(lock) { items.size }

    private fun persist() {
        val arr = JSONArray()
        for (it in items) arr.put(it.toJson())
        runCatching { file.writeText(arr.toString()) }
    }

    private fun load(): MutableList<Item> {
        if (!file.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { i -> itemFromJson(arr.getJSONObject(i)) }
        }.getOrDefault(mutableListOf())
    }

    private fun Item.toJson(): JSONObject = JSONObject().apply {
        put("insert_id", insertId)
        put("event", event)
        put("properties", JSONObject(properties))
        put("client_ts", clientTs)
        put("anon_id", anonId)
        put("external_id", externalId ?: JSONObject.NULL)
    }

    private fun itemFromJson(o: JSONObject): Item = Item(
        insertId = o.getString("insert_id"),
        event = o.getString("event"),
        properties = o.getJSONObject("properties").toMap(),
        clientTs = o.getString("client_ts"),
        anonId = o.getString("anon_id"),
        externalId = if (o.isNull("external_id")) null else o.getString("external_id"),
    )

    private fun JSONObject.toMap(): Map<String, Any?> =
        keys().asSequence().associateWith { k -> if (isNull(k)) null else get(k) }
}
