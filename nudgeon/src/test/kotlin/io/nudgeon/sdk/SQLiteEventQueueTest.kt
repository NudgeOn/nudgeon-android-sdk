package io.nudgeon.sdk

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SQLiteEventQueueTest {
    @get:Rule val temporary = TemporaryFolder()
    private val legacy get() = File(temporary.root, "events.json")
    private val dbFile get() = File(temporary.root, "events.sqlite")
    private fun item(id: String) = EventQueue.Item(id, "purchase", emptyMap(), "2026-09-29T00:00:00Z", "anon", "user")
    private fun json(id: String) = JSONObject().apply {
        put("insert_id", id); put("event", "purchase"); put("properties", JSONObject())
        put("client_ts", "2026-09-29T00:00:00Z"); put("anon_id", "anon"); put("external_id", "user")
    }
    private fun writeLegacy(vararg ids: String) {
        legacy.writeText(JSONArray().apply { ids.forEach { put(json(it)) } }.toString())
    }
    private fun sql(vararg queries: String) {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db -> queries.forEach { db.execSQL(it) } }
    }

    @Test fun reopenPreservesFIFOIdentityAndOnlyAcknowledgesSelectedRows() {
        EventQueue(legacy).use { q ->
            assertTrue(q.enqueue(item("a"))); assertTrue(q.enqueue(item("b"))); assertTrue(q.enqueue(item("c")))
            assertEquals(listOf("a", "b"), q.peek(2).map { it.insertId })
            assertEquals(listOf("a", "b"), q.peek(2).map { it.insertId })
            assertTrue(q.ack(setOf("b")))
        }
        EventQueue(legacy).use { q ->
            assertEquals(listOf("a", "c"), q.peek(10).map { it.insertId })
            assertEquals("user", q.peek(1).first().externalId)
            assertEquals("2026-09-29T00:00:00Z", q.peek(1).first().clientTs)
            assertTrue(q.peek(0).isEmpty())
        }
        assertEquals("SQLite format 3\u0000", dbFile.inputStream().use { String(it.readNBytes(16)) })
    }

    @Test fun nestedJSONSurvivesReopen() {
        val properties = mapOf("true" to true, "false" to false, "zero" to 0, "one" to 1,
            "large" to 9007199254740993L, "fraction" to 1.25, "null" to null,
            "items" to listOf(mapOf("name" to "한글", "count" to 2)),
            "object" to mapOf("values" to listOf(1, "two", null)))
        EventQueue(legacy).use { assertTrue(it.enqueue(item("nested").copy(properties = properties))) }
        EventQueue(legacy).use {
            val actual = JSONObject(it.peek(1).first().properties)
            assertEquals(true, actual.getBoolean("true")); assertEquals(false, actual.getBoolean("false"))
            assertEquals(0, actual.getInt("zero")); assertEquals(1, actual.getInt("one"))
            assertEquals(9007199254740993L, actual.getLong("large"))
            assertEquals(1.25, actual.getDouble("fraction"), 0.0)
            assertTrue(actual.isNull("null"))
            assertEquals("한글", actual.getJSONArray("items").getJSONObject(0).getString("name"))
            assertTrue(actual.getJSONObject("object").getJSONArray("values").isNull(2))
        }
    }

    @Test fun migrationMarkerPreventsReplayingAckedEventsAfterFailedCleanup() {
        writeLegacy("a", "b")
        EventQueue(legacy).use { q ->
            assertEquals(listOf("a", "b"), q.peek(10).map { it.insertId })
            assertFalse(legacy.exists())
            assertTrue(q.ack(setOf("a", "b")))
        }
        writeLegacy("a", "b") // Simulate death after COMMIT but before file deletion.
        EventQueue(legacy).use { assertEquals(0, it.count) }
    }

    @Test fun failedMigrationRollsBackAndRetries() {
        sql("CREATE TABLE events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, insert_id TEXT NOT NULL UNIQUE, payload TEXT NOT NULL)",
            "CREATE TRIGGER fail_import BEFORE INSERT ON events WHEN NEW.insert_id = 'bad' BEGIN SELECT RAISE(ABORT, 'injected'); END")
        writeLegacy("good", "bad")
        EventQueue(legacy).use { q ->
            assertEquals(0, q.count)
            assertTrue(legacy.exists())
            // Prove the first insert was rolled back, not just hidden by the failed open.
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                db.rawQuery("SELECT count(*) FROM events", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            }
            sql("DROP TRIGGER fail_import")
            assertEquals(listOf("good", "bad"), q.peek(10).map { it.insertId })
        }
    }

    @Test fun malformedLegacyIsRetainedAndRepairCanRetry() {
        legacy.writeText("[{broken")
        EventQueue(legacy).use { q ->
            assertFalse(q.enqueue(item("new")))
            assertEquals("[{broken", legacy.readText())
            writeLegacy("old")
            assertTrue(q.enqueue(item("new")))
            assertEquals(listOf("old", "new"), q.peek(10).map { it.insertId })
        }
    }

    @Test fun capAndDuplicatePreserveFIFO() {
        EventQueue(legacy).use { q ->
            repeat(1005) { assertTrue(q.enqueue(item("e$it"))) }
            assertEquals(1000, q.count)
            assertEquals("e5", q.peek(1).first().insertId)
            assertTrue(q.enqueue(item("e5").copy(event = "replacement")))
            assertEquals(1000, q.count)
            assertEquals("purchase", q.peek(1).first().event)
        }
    }

    @Test fun failedInsertAndAckDoNotRemoveCommittedRows() {
        EventQueue(legacy).use { q ->
            assertTrue(q.enqueue(item("a"))); assertTrue(q.enqueue(item("b")))
            sql("CREATE TRIGGER fail_insert BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT, 'injected'); END",
                "CREATE TRIGGER fail_delete BEFORE DELETE ON events WHEN OLD.insert_id = 'b' BEGIN SELECT RAISE(ABORT, 'injected'); END")
            assertFalse(q.enqueue(item("c")))
            assertFalse(q.ack(linkedSetOf("a", "b")))
            assertEquals(listOf("a", "b"), q.peek(10).map { it.insertId })
            sql("DROP TRIGGER fail_insert", "DROP TRIGGER fail_delete")
            assertTrue(q.ack(setOf("a", "b")))
            assertEquals(0, q.count)
        }
    }

    @Test fun concurrentConnectionsDoNotOverwriteEachOther() {
        EventQueue(legacy).use { first ->
            EventQueue(legacy).use { second ->
                val executor = Executors.newFixedThreadPool(4)
                try {
                    val tasks = (0 until 100).map { i -> executor.submit<Boolean> {
                        (if (i % 2 == 0) first else second).enqueue(item("e$i"))
                    } }
                    tasks.forEach { assertTrue(it.get(15, TimeUnit.SECONDS)) }
                    assertEquals(100, first.count)
                    assertEquals(100, second.peek(100).map { it.insertId }.toSet().size)
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Test fun corruptDatabaseIsNotDeleted() {
        dbFile.writeText("not a SQLite database")
        EventQueue(legacy).use { assertFalse(it.enqueue(item("e"))) }
        assertEquals("not a SQLite database", dbFile.readText())
    }
}
