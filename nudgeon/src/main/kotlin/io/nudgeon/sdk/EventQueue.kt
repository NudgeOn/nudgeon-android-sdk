package io.nudgeon.sdk

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SQLite FIFO with a 1000-event oldest-drop limit. Legacy JSON is imported exactly once.
 * Opens lazily on the SDK worker; no database or migration I/O during SDK construction.
 */
internal class EventQueue(private val file: File) : AutoCloseable {
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
    private val databaseFile = File(file.parentFile, file.nameWithoutExtension + ".sqlite")
    private var database: SQLiteDatabase? = null

    fun enqueue(item: Item): Boolean = perform(false) { db ->
        transaction(db) {
            insert(db, item)
            trim(db)
        }
        true
    }

    /** Reading does not acknowledge; failed network delivery leaves rows intact. */
    fun peek(batchSize: Int): List<Item> {
        if (batchSize <= 0) return emptyList()
        return perform(emptyList()) { db ->
            db.rawQuery("SELECT payload FROM events ORDER BY sequence LIMIT ?", arrayOf(batchSize.toString())).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(itemFromJson(JSONObject(cursor.getString(0))))
                }
            }
        }
    }

    fun ack(insertIds: Set<String>): Boolean {
        if (insertIds.isEmpty()) return true
        return perform(false) { db ->
            transaction(db) {
                db.compileStatement("DELETE FROM events WHERE insert_id = ?").use { statement ->
                    for (id in insertIds) {
                        statement.bindString(1, id)
                        statement.executeUpdateDelete()
                    }
                }
            }
            true
        }
    }

    val count: Int get() = perform(0) { db -> scalar(db, "SELECT count(*) FROM events") }

    override fun close() = synchronized(lock) {
        database?.close()
        database = null
    }

    private fun <T> perform(fallback: T, block: (SQLiteDatabase) -> T): T = synchronized(lock) {
        try {
            block(openIfNeeded())
        } catch (_: Exception) {
            // Do not log payloads or silently delete/recreate the DB. Later calls retry opening.
            NudgeOnLog.warn("Event queue storage operation failed; persisted data retained")
            fallback
        }
    }

    private fun openIfNeeded(): SQLiteDatabase {
        database?.let { return it }
        databaseFile.parentFile?.mkdirs()
        // Android's default corruption handler deletes the database; retain it for recovery instead.
        val db = SQLiteDatabase.openDatabase(databaseFile.absolutePath, null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            DatabaseErrorHandler { throw IllegalStateException("Event queue database is corrupt") })
        try {
            db.execSQL("PRAGMA synchronous = FULL")
            transaction(db) {
                check(db.version <= 1) { "Unsupported event queue database version" }
                db.execSQL("CREATE TABLE IF NOT EXISTS events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, insert_id TEXT NOT NULL UNIQUE, payload TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS queue_metadata (key TEXT PRIMARY KEY NOT NULL)")
                if (scalar(db, "SELECT count(*) FROM queue_metadata WHERE key = 'legacy_json_imported'") == 0) {
                    if (file.exists()) {
                        val items = JSONArray(file.readText())
                        for (i in 0 until items.length()) insert(db, itemFromJson(items.getJSONObject(i)))
                    }
                    trim(db)
                    db.execSQL("INSERT INTO queue_metadata (key) VALUES ('legacy_json_imported')")
                }
                db.version = 1
            }
            // Import and marker are already committed. Failure to delete cannot replay old events.
            if (file.exists()) file.delete()
            database = db
            return db
        } catch (error: Exception) {
            db.close()
            throw error
        }
    }

    private fun insert(db: SQLiteDatabase, item: Item) {
        db.execSQL("INSERT OR IGNORE INTO events (insert_id, payload) VALUES (?, ?)",
            arrayOf(item.insertId, item.toJson().toString()))
    }

    private fun trim(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM events WHERE sequence NOT IN (SELECT sequence FROM events ORDER BY sequence DESC LIMIT $maxItems)")
    }

    private fun transaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun scalar(db: SQLiteDatabase, sql: String): Int = db.rawQuery(sql, null).use {
        check(it.moveToFirst())
        it.getInt(0)
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
