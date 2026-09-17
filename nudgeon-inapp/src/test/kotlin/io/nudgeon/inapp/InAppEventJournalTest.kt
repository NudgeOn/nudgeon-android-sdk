package io.nudgeon.inapp

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class InAppEventJournalTest {
    @Test fun restartAcknowledgementAndInstallationIsolation() {
        val dir = Files.createTempDirectory("inapp-journal").toFile()
        try {
            val file = File(dir,"events.json")
            val journal = InAppEventJournal(file,"one")
            journal.append("delivery","presented","")
            journal.append("delivery","impression","")
            val id = journal.events.first().id
            val reopened = InAppEventJournal(file,"one")
            assertEquals(listOf("presented","impression"), reopened.events.map { it.kind })
            assertEquals(id,reopened.events.first().id)
            reopened.acknowledge(id)
            assertEquals(listOf("impression"),InAppEventJournal(file,"one").events.map { it.kind })
            assertTrue(InAppEventJournal(file,"two").events.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun expiryAndFailedWritePreservePendingEvent() {
        val dir = Files.createTempDirectory("inapp-journal").toFile()
        try {
            val file = File(dir,"events.json")
            val journal = InAppEventJournal(file,"one")
            journal.append("old","presented","",System.currentTimeMillis()-604800001)
            assertTrue(InAppEventJournal(file,"one").events.isEmpty())
            journal.append("new","dismiss","")
            val id = journal.events.first().id
            dir.deleteRecursively(); dir.writeText("")
            assertThrows(Exception::class.java) { journal.acknowledge(id) }
            assertEquals(id,journal.events.first().id)
        } finally { dir.deleteRecursively() }
    }
}
