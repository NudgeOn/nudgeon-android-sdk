package io.nudgeon.inapp

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Telemetry receipt, not content-review approval. All callbacks run on the main thread. */
data class InAppTestTransferStatus(
    val phase: Phase, val pendingCount: Int, val acknowledgedCount: Int,
    val reason: String? = null, val canEndSafely: Boolean = false,
) { enum class Phase { IDLE, PENDING, SENDING, ACKNOWLEDGED, FAILED } }

/** One encrypted snapshot per API/key, with stable event IDs across retries and restarts. */
internal class InAppTestDelivery(
    read: () -> String?, private val write: (String) -> Unit,
    private val changed: (InAppTestTransferStatus) -> Unit,
) {
    data class Event(val id: String, val run: String, val kind: String, val detail: String)
    data class Snapshot(val credential: String? = null, val events: List<Event> = emptyList(),
        val activeRun: String? = null, val closing: Boolean = false, val acknowledged: Int = 0,
        val terminalError: String? = null)
    var snapshot = Snapshot(); private set
    private var storageFailed = false
    private var pendingWrite: Snapshot? = null
    var sending = false; private set
    private var epoch = UUID.randomUUID().toString()
    var status = InAppTestTransferStatus(InAppTestTransferStatus.Phase.IDLE,0,0,canEndSafely=true); private set
    val needsRecovery get() = snapshot.credential != null
    val canRetry get() = needsRecovery && snapshot.terminalError == null && !storageFailed
    init {
        read()?.let { saved ->
            require(saved.toByteArray().size <= 256 * 1024)
            val obj = JSONObject(saved); val rows = obj.getJSONArray("events")
            require(rows.length() <= 200)
            snapshot = Snapshot(obj.optString("credential").takeIf { it.isNotEmpty() },
                (0 until rows.length()).map { n -> rows.getJSONObject(n).let { Event(it.getString("id"),it.getString("run"),it.getString("kind"),it.getString("detail")) } },
                obj.optString("activeRun").takeIf { it.isNotEmpty() }, obj.optBoolean("closing"), obj.optInt("acknowledged"),
                obj.optString("terminalError").takeIf { it.isNotEmpty() })
        }
        if (needsRecovery) {
            var next = snapshot.copy(closing = true)
            next.activeRun?.let { run ->
                check(next.events.size < 200)
                next = next.copy(events = next.events + Event(UUID.randomUUID().toString(),run,"failed","PROCESS_RESTARTED"), activeRun = null)
            }
            commit(next) // Recovery only uploads; it never resumes commands or old ads.
        }
        publish()
    }
    fun begin(credential: String) {
        check(!sending && !needsRecovery && !storageFailed) { "PENDING_TEST_RECOVERY" }
        commit(Snapshot(credential=credential)); epoch = UUID.randomUUID().toString(); publish()
    }
    fun retryStorage() { pendingWrite?.let { commit(it); publish() } }
    fun active(run: String) { check(!storageFailed); commit(snapshot.copy(activeRun=run)); publish() }
    fun append(run: String, kind: String, detail: String) {
        check(!storageFailed) { "STORAGE_ERROR" }
        check(needsRecovery)
        if (snapshot.events.size >= 199) { commit(snapshot.copy(terminalError="QUEUE_FULL")); publish(); error("QUEUE_FULL") }
        commit(snapshot.copy(events=snapshot.events+Event(UUID.randomUUID().toString(),run,kind,detail.take(200)),
            activeRun=if (kind in setOf("dismiss","failed")) null else snapshot.activeRun)); publish()
    }
    fun close() {
        retryStorage()
        val next = snapshot.activeRun?.let { run -> snapshot.copy(
            events=snapshot.events+Event(UUID.randomUUID().toString(),run,"failed","SESSION_ENDED"),activeRun=null) } ?: snapshot
        commit(next.copy(closing=next.credential!=null)); publish()
    }
    /** Explicit discard never produces a server acknowledgement. */
    fun discard() { commit(Snapshot()); storageFailed=false; epoch=UUID.randomUUID().toString(); publish() }
    suspend fun flush(send: suspend (String, JSONObject, String) -> Unit, httpStatus: (Exception) -> Int?) {
        if (sending || !canRetry) return
        val token = snapshot.credential ?: return
        sending=true; val current=epoch; publish(InAppTestTransferStatus.Phase.SENDING)
        try {
            while (snapshot.events.isNotEmpty()) {
                val e = snapshot.events.first()
                send("runs/${e.run}/events", JSONObject().put("event_id",e.id).put("kind",e.kind).put("detail",e.detail),token)
                if (current!=epoch) return
                commit(snapshot.copy(events=snapshot.events.filter { it.id!=e.id },acknowledged=snapshot.acknowledged+1))
                publish(InAppTestTransferStatus.Phase.SENDING)
            }
            if (snapshot.closing) {
                try { send("end",JSONObject(),token) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (httpStatus(e)!=401) throw e }
                if (current!=epoch) return
                commit(snapshot.copy(credential=null,closing=false))
            }
            sending=false; publish()
        } catch (e: CancellationException) {
            sending=false; if (current==epoch) publish(); throw e
        } catch (e: Exception) {
            if (current!=epoch) return
            val code=httpStatus(e)
            if (code in setOf(400,401,403,404,409,410,422)) {
                runCatching { commit(snapshot.copy(terminalError="HTTP_$code")) }
            }
            sending=false
            publish(InAppTestTransferStatus.Phase.FAILED,if(storageFailed) "STORAGE_ERROR" else snapshot.terminalError ?: code?.let { "HTTP_$it" } ?: "NETWORK_ERROR")
        } finally { sending=false; if(current!=epoch) publish() }
    }
    private fun commit(next: Snapshot) {
        val rows=JSONArray()
        next.events.forEach { e -> rows.put(JSONObject().put("id",e.id).put("run",e.run).put("kind",e.kind).put("detail",e.detail)) }
        try {
            write(JSONObject().put("credential",next.credential ?: "").put("events",rows)
                .put("activeRun",next.activeRun ?: "").put("closing",next.closing)
                .put("acknowledged",next.acknowledged).put("terminalError",next.terminalError ?: "").toString())
            snapshot=next; pendingWrite=null; storageFailed=false
        } catch (e: Exception) { pendingWrite=next; storageFailed=true; publish(InAppTestTransferStatus.Phase.FAILED,"STORAGE_ERROR"); throw e }
    }
    private fun publish(phase: InAppTestTransferStatus.Phase?=null, reason: String?=null) {
        val error=reason ?: snapshot.terminalError ?: if(storageFailed) "STORAGE_ERROR" else null
        val inferred=when { error!=null -> InAppTestTransferStatus.Phase.FAILED
            snapshot.events.isNotEmpty() || snapshot.closing -> InAppTestTransferStatus.Phase.PENDING
            snapshot.acknowledged>0 -> InAppTestTransferStatus.Phase.ACKNOWLEDGED
            else -> InAppTestTransferStatus.Phase.IDLE }
        status=InAppTestTransferStatus(phase ?: inferred,snapshot.events.size,snapshot.acknowledged,error,
            error==null && !sending && snapshot.events.isEmpty() && snapshot.activeRun==null && !snapshot.closing)
        changed(status)
    }
}
