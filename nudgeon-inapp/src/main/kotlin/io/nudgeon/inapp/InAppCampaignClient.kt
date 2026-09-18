package io.nudgeon.inapp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.util.UUID

/** Opt-in public campaigns; no user identity or private content is exposed to the web document. */
class InAppCampaignClient(
    private val application: Application,
    private val configuration: InAppTestClient.Configuration,
    private val host: () -> Activity?, private val isAllowed: () -> Boolean,
    private val onAction: (InAppAction) -> Unit, private val onDiagnostic: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = InAppInstallationStore(application, sha256(configuration.apiUrl + "|" + configuration.sdkKey))
    private var credential: String? = null
    private var lifecycleEvents = false
    private var enabled = false; private var busy = false; private var shown = false
    private var generation = UUID.randomUUID(); private var session = UUID.randomUUID()
    private var resumed: Activity? = null; private var renderer: InAppRenderer? = null; private var delivery: String? = null
    private var polling: Job? = null; private var expiry: Job? = null; private var flushing = false
    private var journal: InAppEventJournal? = null
    private var retryAt = 0L; private var failures = 0
    private var launchWindow: InAppLaunchWindow? = null
    private var launchTimeout: Job? = null
    private var launchCompletion: ((InAppLaunchResult) -> Unit)? = null
    private fun monotonicNow() = System.nanoTime() / 1_000_000
    private fun finishLaunch(result: InAppLaunchResult, atMillis: Long = monotonicNow()) {
        val resolved = launchWindow?.complete(result, atMillis) ?: return
        launchTimeout?.cancel(); launchTimeout = null
        val completion = launchCompletion; launchCompletion = null
        if (completion != null) android.os.Handler(Looper.getMainLooper()).post { completion(resolved) }
    }
    /** Call instead of enable(), with a resumed, focused host after its launch screen/consent.
     * One attempt per API/key per process, including Activity/owner recreation. */
    fun enableAfterLaunch(timeoutSeconds: Double = 3.0, onResult: (InAppLaunchResult) -> Unit = {}): Boolean {
        main()
        if (enabled) { android.os.Handler(Looper.getMainLooper()).post { onResult(InAppLaunchResult.ALREADY_HANDLED) }; return false }
        enabled = true
        resumed = host()?.takeIf { !it.isFinishing && !it.isDestroyed }
        startPolling()
        if (!InAppLaunchRegistry.process.claim(sha256(configuration.apiUrl + "|" + configuration.sdkKey))) {
            android.os.Handler(Looper.getMainLooper()).post { onResult(InAppLaunchResult.ALREADY_HANDLED) }; return false
        }
        stop("session_ended"); session = UUID.randomUUID()
        val window = InAppLaunchWindow(timeoutSeconds, monotonicNow())
        launchWindow = window; launchCompletion = onResult
        launchTimeout = scope.launch {
            delay((window.deadline - monotonicNow()).coerceAtLeast(0))
            if (launchWindow === window && window.result == null) stop("launch_timeout")
        }
        trigger(JSONObject().put("type","launch"))
        return true
    }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { resumed = activity }
        override fun onActivityPaused(activity: Activity) { if (resumed === activity) { resumed = null; stop("background") } }
        override fun onActivityDestroyed(activity: Activity) { if (resumed === activity) { resumed = null; stop("host_destroyed") } }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    }
    init {
        val u = URI(configuration.apiUrl)
        require(u.scheme == "https" || (u.scheme == "http" && u.host in setOf("localhost", "127.0.0.1", "10.0.2.2")))
        require(u.userInfo == null && u.query == null && u.fragment == null)
        credential = store.read(); credential?.let { journal = makeJournal(it) }; application.registerActivityLifecycleCallbacks(lifecycle)
    }
    private fun main() { check(Looper.myLooper() == Looper.getMainLooper()) }
    fun enable() { main(); if (enabled) return; enabled = true; resumed = host()?.takeIf { !it.isFinishing && !it.isDestroyed }; startPolling(); foreground() }
    fun disable() { main(); enabled = false; stop("disabled"); polling?.cancel(); polling = null; scope.launch { runCatching { flush() } } }
    fun contextChanged() { stop("context_changed") }
    private fun stop(reason: String, failure: Boolean = false) {
        main(); generation = UUID.randomUUID()
        delivery?.let { val event = terminationEvent(reason, failure, shown, lifecycleEvents); queue(it,event.first,event.second) }
        shown = false; renderer?.close(); renderer = null; delivery = null; expiry?.cancel()
        finishLaunch(if (reason == "launch_timeout") InAppLaunchResult.TIMED_OUT else if (failure) InAppLaunchResult.FAILED else InAppLaunchResult.CANCELLED)
    }
    fun foreground() { main(); if (!enabled) return; stop("session_ended"); session = UUID.randomUUID(); trigger(JSONObject().put("type","foreground")) }
    fun screen(name: String) { main(); stop("screen_changed"); trigger(JSONObject().put("type","screen").put("name",name)) }
    fun track(name: String) { main(); trigger(JSONObject().put("type","event").put("name",name)) }
    suspend fun forgetInstallation() { main(); disable(); runCatching { request("revoke",JSONObject()) }; credential = null; store.clear(); journal?.clear(); journal = null }
    fun destroy() { disable(); application.unregisterActivityLifecycleCallbacks(lifecycle); scope.cancel() }
    private fun trigger(trigger: JSONObject) {
        val launch = if (trigger.optString("type") == "launch") launchWindow else null
        val activity = host()?.takeIf { it === resumed && !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() }
        if (activity == null || !enabled || !isAllowed() || busy || renderer != null) {
            if (launch != null) finishLaunch(InAppLaunchResult.BLOCKED)
            return
        }
        busy = true; val current = generation; val sessionId = session
        scope.launch {
            try {
                if (credential == null) {
                    val c = request("installations",JSONObject().put("platform","android")).getString("credential")
                    if (!enabled || generation != current) return@launch
                    val pending = makeJournal(c); store.write(c); credential = c; journal = pending
                }
                flush()
                if (!enabled || current != generation) return@launch
                val response = request("decisions",JSONObject().put("request_key",UUID.randomUUID()).put("session_id",sessionId).put("trigger",trigger))
                val artifact = response.optJSONObject("delivery")
                if (artifact == null) { if (current == generation) finishLaunch(InAppLaunchResult.NO_CAMPAIGN); return@launch }
                val id = artifact.getString("id")
                if (!enabled || current != generation || activity !== resumed || !isAllowed()) { queue(id,if(artifact.optBoolean("lifecycle_events")) "cancelled" else "failed",if(artifact.optBoolean("lifecycle_events")) "host_blocked" else "HOST_BLOCKED"); if (current == generation) finishLaunch(InAppLaunchResult.BLOCKED); return@launch }
                delivery = id; lifecycleEvents = artifact.optBoolean("lifecycle_events"); validateArtifact(artifact)
                var presentationTime: Long? = null
                renderer = InAppRenderer(activity,artifact,configuration.allowedSchemes,configuration.allowedWebHosts,
                    showHideToday = true, beforeShow = { show -> scope.launch {
                        try {
                            val authorization = request("deliveries/$id/authorize",JSONObject())
                            if (delivery != id) return@launch
                            if (!enabled || current != generation || activity !== resumed || !isAllowed()) { stop("host_blocked"); return@launch }
                            if (launch != null && !launch.canPresent(monotonicNow())) { stop("launch_timeout"); return@launch }
                            val millis = Instant.parse(authorization.getString("expires_at")).toEpochMilli() - System.currentTimeMillis()
                            if (millis <= 0) { stop("display_timeout"); return@launch }; presentationTime = monotonicNow(); show()
                            expiry = scope.launch { delay(minOf(millis,290000)); if (delivery == id) stop("display_timeout") }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { if (delivery == id) stop("DISPLAY_AUTHORIZATION_FAILED",true); onDiagnostic("DISPLAY_AUTHORIZATION_FAILED") }
                    } }, canPresent = { enabled && generation == current && activity === resumed && isAllowed() && (launch == null || shown || launch.canPresent(monotonicNow())) },
                    onEvent = { kind, detail -> if (kind == "presented") { shown = true; finishLaunch(InAppLaunchResult.SHOWN, presentationTime ?: monotonicNow()) }; if (kind == "failed") finishLaunch(InAppLaunchResult.FAILED); if(kind == "failed" && detail == "RUN_EXPIRED" && lifecycleEvents) queue(id,"cancelled","display_timeout") else queue(id,kind,detail) },
                    onEnd = { action -> if (delivery == id) { shown = false; renderer = null; delivery = null; expiry?.cancel(); if (action != null) onAction(action) } })
                renderer!!.prepare()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == generation) stop("CAMPAIGN_REQUEST_FAILED",true); onDiagnostic("CAMPAIGN_REQUEST_FAILED"); if (e is InAppHttpError && e.status == 401) disable() }
            finally { busy = false }
        }
    }
    private fun queue(id: String, kind: String, detail: String) { runCatching { journal?.append(id,kind,detail) }.onFailure { onDiagnostic("EVENT_STORAGE_FAILED") }; onDiagnostic("$kind:$detail") }
    private fun startPolling() {
        polling?.cancel(); polling = scope.launch {
            while (isActive && enabled) {
                if (resumed != null) try { flush(); val id = delivery; if (shown && id != null) { val status = request("deliveries/$id"); if(!status.getBoolean("active") && delivery == id) stop(status.optString("reason").takeIf { it.isNotBlank() && it != "null" } ?: "delivery_inactive") } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { onDiagnostic("CAMPAIGN_SYNC_FAILED"); if (e is InAppHttpError && e.status == 401) { disable(); return@launch } }
                delay(3000)
            }
        }
    }
    private suspend fun flush() {
        if (flushing || System.currentTimeMillis() < retryAt) return; flushing = true
        try { while (journal?.events?.isNotEmpty() == true && credential != null) {
            val e = journal!!.events.first()
            try { request("deliveries/${e.delivery}/events",JSONObject().put("event_id",e.id).put("kind",e.kind).put("detail",e.detail).put("occurred_at",e.occurredAt)) }
            catch (error: InAppHttpError) { if (error.status !in setOf(400,404,409)) throw error }
            journal?.acknowledge(e.id)
        }
            failures = 0; retryAt = 0
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            failures = minOf(failures + 1, 6)
            retryAt = System.currentTimeMillis() + minOf(60000L, 1000L shl failures) + kotlin.random.Random.nextLong(1000)
            throw e
        } finally { flushing = false }
    }
    private fun makeJournal(token: String) = InAppEventJournal(
        java.io.File(application.noBackupFilesDir, "io.nudgeon.inapp." + sha256(configuration.apiUrl + "|" + configuration.sdkKey) + ".events.json"), sha256(token))
    private suspend fun request(path: String, body: JSONObject? = null): JSONObject {
        val token = credential
        return withContext(Dispatchers.IO) {
            val c = URI(configuration.apiUrl.trimEnd('/') + "/v1/in-app/live/" + path).toURL().openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 10000; c.readTimeout = 10000; c.requestMethod = if (body == null) "GET" else "POST"
            c.setRequestProperty("X-NudgeOn-In-App-Capabilities","campaign-time-zone")
            c.setRequestProperty("Authorization","Bearer ${configuration.sdkKey}"); if (token != null) c.setRequestProperty("X-NudgeOn-Installation",token)
            try { if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type","application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
                if (c.responseCode !in 200..299) throw InAppHttpError(c.responseCode)
                val bytes = c.inputStream.use { input -> val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size()+n <= 40*1024*1024); out.write(buffer,0,n) }; out.toByteArray() }
                JSONObject(bytes.toString(Charsets.UTF_8))
            } finally { c.disconnect() }
        }
    }
}
