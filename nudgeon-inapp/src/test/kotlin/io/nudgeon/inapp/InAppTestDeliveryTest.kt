package io.nudgeon.inapp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class InAppTestDeliveryTest {
    class Http(val code: Int): Exception()
    private class Store {
        var value: String?=null; var fail=false
        fun make()=InAppTestDelivery({value},{check(!fail); value=it},{})
    }
    private val code: (Exception)->Int? = { (it as? Http)?.code }
    @Test fun offlineEndRestartAndOrderedReceipts() = runBlocking {
        val store=Store(); val client=store.make(); client.begin("a"); client.active("run")
        listOf("presented","impression","dismiss").forEach { client.append("run",it,"") }
        val ids=client.snapshot.events.map { it.id }; client.close()
        client.flush({_,_,_->error("offline")},code)
        assertEquals(InAppTestTransferStatus.Phase.FAILED,client.status.phase); assertEquals(3,client.status.pendingCount)
        val restored=store.make(); val sent=mutableListOf<String>(); val paths=mutableListOf<String>()
        restored.flush({path,body,token -> assertEquals("a",token); paths.add(path); if(body.has("event_id")) sent.add(body.getString("event_id")) },code)
        assertEquals(ids,sent); assertEquals("end",paths.last()); assertFalse(restored.needsRecovery)
        assertEquals(InAppTestTransferStatus.Phase.ACKNOWLEDGED,restored.status.phase); assertTrue(restored.status.canEndSafely)
    }
    @Test fun lostResponseRetriesSameIdWithoutDoubleCount() = runBlocking {
        val store=Store(); val client=store.make(); client.begin("a"); client.append("r","dismiss",""); client.close()
        val server=mutableSetOf<String>(); var first=true
        val send: suspend (String,JSONObject,String)->Unit = {_,body,_->
            if(body.has("event_id")) { server.add(body.getString("event_id")); if(first) { first=false; error("response lost") } }
        }
        client.flush(send,code); assertEquals(0,client.status.acknowledgedCount)
        val restored=store.make(); restored.flush(send,code)
        assertEquals(1,server.size); assertEquals(1,restored.status.acknowledgedCount)
    }
    @Test fun rejectedExpiredSessionNeverAcknowledgesOrEnds() = runBlocking {
        for(status in listOf(401,404,409)) {
            val client=Store().make(); client.begin("a"); client.append("r","impression",""); client.close()
            client.flush({_,_,_->throw Http(status)},code)
            assertEquals(InAppTestTransferStatus.Phase.FAILED,client.status.phase); assertEquals("HTTP_$status",client.status.reason)
            assertEquals(1,client.status.pendingCount); assertFalse(client.status.canEndSafely)
            assertThrows(IllegalStateException::class.java) { client.begin("other") }
            client.discard(); assertEquals(InAppTestTransferStatus.Phase.IDLE,client.status.phase)
        }
    }
    @Test fun failedAcknowledgementWriteCanBeRetried() = runBlocking {
        val store=Store(); val client=store.make(); client.begin("a"); client.append("r","dismiss","")
        store.fail=true; client.flush({_,_,_->},code)
        assertEquals(InAppTestTransferStatus.Phase.FAILED,client.status.phase); assertEquals(0,client.status.acknowledgedCount)
        store.fail=false; client.retryStorage(); assertEquals(1,client.status.acknowledgedCount)
    }
    @Test fun restartInterruptsActiveRunWithoutResumingAd() {
        val store=Store(); val client=store.make(); client.begin("a"); client.active("r"); client.append("r","presented","")
        val restored=store.make(); assertNull(restored.snapshot.activeRun); assertTrue(restored.snapshot.closing)
        assertEquals(listOf("presented","failed"),restored.snapshot.events.map { it.kind })
        assertEquals("PROCESS_RESTARTED",restored.snapshot.events.last().detail)
        assertEquals(2,store.make().snapshot.events.size)
    }
    @Test fun closeDuringInFlightEventWaitsForReceipt() = runBlocking {
        val client=Store().make(); client.begin("a"); client.append("r","dismiss","")
        val paths=mutableListOf<String>()
        client.flush({path,_,_->paths.add(path); if(path!="end") client.close()},code)
        assertEquals(listOf("runs/r/events","end"),paths); assertTrue(client.status.canEndSafely)
    }
    @Test fun discardInFlightNeverClaimsReceipt() = runBlocking {
        val client=Store().make(); client.begin("a"); client.append("r","dismiss","")
        client.flush({_,_,_->client.discard()},code)
        assertEquals(InAppTestTransferStatus.Phase.IDLE,client.status.phase); assertEquals(0,client.status.acknowledgedCount)
    }
    @Test fun snapshotLeasePreventsConcurrentWritersAndReleasesOnce() {
        val first=InAppTestStoreLease("test")
        assertThrows(IllegalStateException::class.java) { InAppTestStoreLease("test") }
        first.close(); val second=InAppTestStoreLease("test"); first.close()
        assertThrows(IllegalStateException::class.java) { InAppTestStoreLease("test") }; second.close()
    }
}
