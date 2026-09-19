package io.nudgeon.inapp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Explicitly paired test mode. Does not enable production campaigns or use push tokens. */
class InAppTestClient(
    private val application: Application,
    private val configuration: Configuration,
    private val host: () -> Activity?,
    private val isAllowed: () -> Boolean,
    private val onAction: (InAppAction) -> Unit,
    private val onDiagnostic: (String) -> Unit = {},
    private val onTransferStatus: (InAppTestTransferStatus) -> Unit = {},
) {
    data class Configuration(val apiUrl: String, val sdkKey: String, val allowedSchemes: Set<String> = emptySet(), val allowedWebHosts: Set<String> = emptySet())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var credential: String? = null
    private var polling: Job? = null
    private var generation = UUID.randomUUID().toString()
    private var renderer: InAppRenderer? = null
    private var runId: String? = null
    private var resumed: Activity? = null
    private val storeLease: InAppTestStoreLease
    private val delivery: InAppTestDelivery
    private var transferJob: Job? = null
    val transferStatus: InAppTestTransferStatus get() = delivery.status
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { resumed = activity }
        override fun onActivityPaused(activity: Activity) { if (resumed === activity) { resumed = null; contextChanged() } }
        override fun onActivityDestroyed(activity: Activity) { if (resumed === activity) { resumed = null; contextChanged() } }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    }
    init {
        val uri = URI(configuration.apiUrl)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) { "HTTPS required" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        val namespace = "test-" + java.security.MessageDigest.getInstance("SHA-256")
            .digest((configuration.apiUrl + "\n" + configuration.sdkKey).toByteArray()).joinToString("") { "%02x".format(it) }
        val store = InAppInstallationStore(application, namespace)
        storeLease = InAppTestStoreLease(namespace)
        delivery = try { InAppTestDelivery({ store.read() }, { store.write(it) }, onTransferStatus) }
        catch (e: Exception) { storeLease.close(); throw e }
        application.registerActivityLifecycleCallbacks(lifecycle)
        startTransfer()
    }
    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    /** Call after explicit user consent; show the returned confirmation number next to the console number. */
    suspend fun pair(token: String, label: String = "Android test device"): InAppPairing {
        mainThread(); end(); flush()
        check(!delivery.needsRecovery && !delivery.sending) { "PENDING_TEST_RECOVERY" }
        val current = generation
        val result = request("pair", JSONObject().put("token", token).put("label", label.take(80)).put("platform", "android").put("sdk_version", "inapp-test/1"))
        check(current == generation) { "SESSION_CLOSED" }
        delivery.begin(result.getString("credential"), result.getString("expires_at"))
        credential = result.getString("credential")
        // A client created from a resumed Activity can start immediately.
        resumed = host()?.takeIf { !it.isFinishing && !it.isDestroyed }
        val pairing = InAppPairing(result.getString("id"), result.getString("confirmation_code"), result.getString("expires_at"))
        onDiagnostic("CONFIRM_DEVICE:${pairing.confirmationCode}"); startPolling(); return pairing
    }
    /** Open only from an app-owned settings/debug action after the user requests testing. */
    fun showConnection(activity: Activity) {
        mainThread()
        val input = android.widget.EditText(activity).apply { hint = "Pairing code"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        android.app.AlertDialog.Builder(activity).setTitle("NudgeOn test device")
            .setMessage("Paste the code from NudgeOn. Test actions may open app screens.").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Connect") { _, _ ->
                scope.launch {
                    try {
                        val result = pair(input.text.toString().trim())
                        android.app.AlertDialog.Builder(activity).setTitle(result.confirmationCode)
                            .setMessage("Confirm this number in NudgeOn, then keep the app open. Connection expires after 30 minutes.")
                            .setPositiveButton("OK", null).show()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { android.app.AlertDialog.Builder(activity).setTitle("Connection failed").setMessage("Create a new pairing code and try again.").setPositiveButton("OK",null).show() }
                }
            }.show()
    }
    /** Stops presentation immediately; receipt failures stay durably queued. */
    fun end() {
        mainThread(); credential = null; generation = UUID.randomUUID().toString()
        polling?.cancel(); polling = null; renderer?.close(); renderer = null; runId = null
        try { delivery.close() } catch (_: Exception) { onDiagnostic("TEST_STORAGE_ERROR"); return }
        startTransfer()
    }
    /** Upload stored records only. Never resumes commands or displays an old ad. */
    fun retryPendingEvents() {
        mainThread()
        try { delivery.retryStorage() } catch (_: Exception) { onDiagnostic("TEST_STORAGE_ERROR"); return }
        scope.launch { flush(); startTransfer() }
    }
    /** Explicit abandonment, never represented as server receipt. */
    fun discardPendingEvents() {
        mainThread(); val old=delivery.snapshot.credential
        credential=null; generation=UUID.randomUUID().toString(); polling?.cancel(); polling=null
        renderer?.close(); renderer=null; runId=null
        try { delivery.discard() } catch (_: Exception) { onDiagnostic("TEST_STORAGE_ERROR"); return }
        if(old!=null) scope.launch { runCatching { request("end",JSONObject(),old) } }
    }
    /** Call on identity, consent or screen eligibility changes. */
    fun contextChanged() {
        mainThread(); generation = UUID.randomUUID().toString()
        runId?.let { queue(it, "failed", "CONTEXT_CHANGED") }; renderer?.close(); renderer = null; runId = null
    }
    fun destroy() { end(); application.unregisterActivityLifecycleCallbacks(lifecycle); scope.cancel(); storeLease.close() }
    private fun queue(run: String, kind: String, detail: String = "") {
        try { delivery.append(run,kind,detail); startTransfer() } catch (_: Exception) { onDiagnostic("TEST_STORAGE_ERROR") }
        onDiagnostic("$kind:$detail")
    }
    private fun startTransfer() {
        if (transferJob!=null || !delivery.canRetry || (delivery.snapshot.events.isEmpty() && !delivery.snapshot.closing)) return
        val job=scope.launch(start=CoroutineStart.LAZY) {
            var wait=1000L
            try {
                while(isActive) {
                    flush()
                    if(!delivery.canRetry || (delivery.snapshot.events.isEmpty() && !delivery.snapshot.closing)) break
                    delay(wait); wait=(wait*2).coerceAtMost(30000)
                }
            } finally { transferJob=null }
        }
        transferJob=job
        job.start()
    }
    private fun startPolling() {
        polling?.cancel(); polling = scope.launch {
            while (isActive && credential != null) {
                try {
                    if (resumed != null) {
                        flush()
                        check(delivery.snapshot.events.isEmpty()) { "PENDING_TEST_RECOVERY" }
                        val commands = request("commands")
                        delivery.updateSessionExpiry(commands.optString("expires_at").takeIf { it.isNotEmpty() })
                        val run = commands.optJSONObject("run")
                        if (runId != null && run?.optString("id") != runId) contextChanged()
                        if (commands.getString("state") == "active" && run?.optString("state") == "queued" && runId == null) render(run.getString("id"))
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: InAppHttpError) { if (e.status == 401) { end(); return@launch }; onDiagnostic("TEST_NETWORK_ERROR") }
                catch (_: Exception) { onDiagnostic("TEST_NETWORK_ERROR") }
                delay(3000)
            }
        }
    }
    private suspend fun flush() {
        delivery.flush({ path,body,token ->
            check(request(path,body,token).optBoolean("ok",false)) { "INVALID_RECEIPT" }
        }, { (it as? InAppHttpError)?.status })
    }
    private suspend fun render(id: String) {
        val activity = host()?.takeIf { it === resumed && !it.isFinishing && !it.isDestroyed } ?: return
        if (!isAllowed() || !activity.hasWindowFocus()) return
        val current = generation
        val artifact = request("runs/$id/claim", JSONObject())
        try {
            validateArtifact(artifact)
            if (generation != current || activity !== resumed || !isAllowed()) { queue(id, "failed", "HOST_BLOCKED"); return }
            delivery.active(id, artifact.getString("revision_id"), artifact.getString("expires_at"))
            runId = id
            renderer = InAppRenderer(activity, artifact, configuration.allowedSchemes, configuration.allowedWebHosts,
                canPresent = { generation == current && activity === resumed && isAllowed() },
                onEvent = { kind, detail -> queue(id, kind, detail) },
                onEnd = { action -> if (runId == id) { renderer = null; runId = null; if (action != null) onAction(action) } })
            renderer!!.prepare()
        } catch (e: Exception) { renderer?.close(); renderer = null; runId = null; queue(id, "failed", e.message?.take(100) ?: "RENDER_FAILED") }
    }
    private suspend fun request(path: String, body: JSONObject? = null, token: String? = credential): JSONObject = withContext(Dispatchers.IO) {
        val connection = URI(configuration.apiUrl.trimEnd('/') + "/v1/in-app/test/" + path).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10000; connection.readTimeout = 10000; connection.requestMethod = if (body == null) "GET" else "POST"
        connection.setRequestProperty("Authorization", "Bearer ${configuration.sdkKey}")
        if (token != null) connection.setRequestProperty("X-NudgeOn-Test-Token", token)
        try {
            if (body != null) { connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json"); connection.outputStream.use { it.write(body.toString().toByteArray()) } }
            if (connection.responseCode !in 200..299) throw InAppHttpError(connection.responseCode)
            val bytes = connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = stream.read(buffer); if (n < 0) break; require(output.size() + n <= 40 * 1024 * 1024); output.write(buffer, 0, n) }; output.toByteArray()
            }
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}
