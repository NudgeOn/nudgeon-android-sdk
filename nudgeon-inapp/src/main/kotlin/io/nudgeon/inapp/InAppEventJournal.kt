package io.nudgeon.inapp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/** Atomic, ordered telemetry only. Scoped to one installation; excluded from backup by the caller. */
internal class InAppEventJournal(private val file: File, private val owner: String) {
    data class Event(val id: String, val delivery: String, val kind: String, val detail: String, val occurredAt: String, val createdAt: Long)
    var events: List<Event> = emptyList(); private set
    init {
        file.parentFile?.mkdirs()
        if (file.exists()) {
            require(file.length() <= 2 * 1024 * 1024)
            val snapshot = JSONObject(file.readText()); val rows = snapshot.getJSONArray("events")
            require(rows.length() <= 1000)
            if (snapshot.getString("owner") == owner) events = (0 until rows.length()).map { n ->
                val e = rows.getJSONObject(n)
                Event(e.getString("id"), e.getString("delivery"), e.getString("kind"), e.getString("detail"), e.getString("occurredAt"), e.getLong("createdAt"))
            }
        }
        commit(events.filter { it.createdAt > System.currentTimeMillis() - 604800000 })
    }
    fun append(delivery: String, kind: String, detail: String, now: Long = System.currentTimeMillis()) {
        val next = events.filter { it.createdAt > now - 604800000 }
        check(next.size < 1000)
        commit(next + Event(UUID.randomUUID().toString(), delivery, kind, detail.take(200), Instant.ofEpochMilli(now).toString(), now))
    }
    fun acknowledge(id: String) = commit(events.filter { it.id != id })
    fun clear() = commit(emptyList())
    private fun commit(next: List<Event>) {
        val rows = JSONArray()
        next.forEach { e -> rows.put(JSONObject().put("id",e.id).put("delivery",e.delivery).put("kind",e.kind).put("detail",e.detail).put("occurredAt",e.occurredAt).put("createdAt",e.createdAt)) }
        val temp = File(file.path + ".tmp")
        FileOutputStream(temp).use { it.write(JSONObject().put("owner",owner).put("events",rows).toString().toByteArray()); it.fd.sync() }
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        events = next
    }
}
