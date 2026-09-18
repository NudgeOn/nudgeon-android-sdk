package io.nudgeon.inapp

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

internal class InAppRenderer(
    private val activity: Activity, private val artifact: JSONObject,
    private val allowedSchemes: Set<String>, private val allowedHosts: Set<String>,
    private val showHideToday: Boolean = false, private val autoDismissSeconds: Double? = null, private val beforeShow: (((() -> Unit)) -> Unit)? = null,
    private val canPresent: () -> Boolean, private val onEvent: (String, String) -> Unit, private val onEnd: (InAppAction?) -> Unit,
) {
    private val url = "https://nudgeon.invalid/index.html"
    private val nonce = UUID.randomUUID().toString()
    private val bridgeContext = JSONObject().put("time_zone", artifact.optString("time_zone", "UTC"))
    private val id = artifact.getString("id")
    private val manifest = artifact.getJSONObject("manifest")
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var dialog: Dialog? = null
    private var ended = false
    private var ready = false
    private var presented = false
    private var impression = false
    private var actionTaken = false
    private val replies = mutableMapOf<String, String>()
    private val timeout = Runnable { fail("CONTENT_TIMEOUT") }
    private val expiry = Runnable { fail("RUN_EXPIRED") }
    private val impressionTimer = Runnable { if (dialog?.isShowing == true && canPresent()) recordImpression() }
    @Suppress("SetJavaScriptEnabled")
    fun prepare() {
        check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { "UNSUPPORTED_WEBVIEW" }
        val content = artifact.getString("html").toByteArray(Charsets.UTF_8)
        val view = WebView(activity); web = view
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.apply { javaScriptEnabled = true; domStorageEnabled = false; allowFileAccess = false; allowContentAccess = false; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false); mediaPlaybackRequiresUserGesture = true; blockNetworkLoads = true }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onJsAlert(v: WebView?, u: String?, m: String?, r: JsResult): Boolean { r.cancel(); return true }
            override fun onJsConfirm(v: WebView?, u: String?, m: String?, r: JsResult): Boolean { r.cancel(); return true }
            override fun onJsPrompt(v: WebView?, u: String?, m: String?, d: String?, r: JsPromptResult): Boolean { r.cancel(); return true }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse {
                if (request.isForMainFrame && request.url.toString() == url) return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(content))
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(v: WebView, loaded: String) { if (!ended && loaded == url) { onEvent("bridge_ready", ""); v.evaluateJavascript("window.__nudgeonConnect(${JSONObject.quote(id)},${JSONObject.quote(nonce)},$bridgeContext)", null) } }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) { if (r.isForMainFrame) fail("WEBVIEW_ERROR") }
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean { fail("WEBVIEW_TERMINATED"); return true }
        }
        WebViewCompat.addWebMessageListener(view, "NudgeOnNative", setOf("https://nudgeon.invalid")) { _, message, origin, mainFrame, _ ->
            if (!ended && mainFrame && origin.toString() == "https://nudgeon.invalid") runCatching { message.data }.getOrNull()?.let(::receive)
        }
        // Preparation happens offscreen; the native app remains usable until content is ready.
        view.layout(0, 0, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
        handler.postDelayed(timeout, 5000); view.loadUrl(url)
    }
    private fun receive(raw: String) {
        if (raw.toByteArray().size > 8192 || replies.size >= 200) return
        val m = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (m.optInt("protocol") != 1 || m.optString("execution_id") != id || m.optString("nonce") != nonce) return
        val request = m.optString("request_id"); if (request.isEmpty() || request.length > 64) return
        replies[request]?.let { web?.evaluateJavascript(it, null); return }
        when (m.optString("method")) {
            "ready" -> { respond(request); if (!ready) { ready = true; onEvent("content_ready", ""); if (beforeShow != null) beforeShow.invoke { show() } else show() } }
            "log" -> { onEvent("log", "JS_ERROR"); respond(request) }
            "dismiss" -> if (presented) { respond(request); finish("html_close") } else respond(request, "NOT_PRESENTED")
            "hideToday" -> when {
                !presented -> respond(request, "NOT_PRESENTED")
                !showHideToday -> respond(request, "LIVE_CAMPAIGN_REQUIRED")
                else -> { respond(request); hideToday() }
            }
            "performAction" -> {
                if (!presented || actionTaken) { respond(request, "NOT_PRESENTED"); return }
                val actionId = m.optJSONObject("payload")?.optString("action_id") ?: ""
                val action = manifest.getJSONObject("actions").optJSONObject(actionId)
                if (action == null) { respond(request, "ACTION_NOT_ALLOWED"); return }
                val type = action.optString("type"); val target = action.optString("url"); val uri = Uri.parse(target)
                val allowed = when (type) {
                    "deep_link" -> uri.scheme?.lowercase() in allowedSchemes && uri.scheme?.lowercase() !in setOf("file","data","javascript","http","https","intent","content") && uri.userInfo == null
                    "open_url" -> uri.scheme == "https" && uri.host?.lowercase() in allowedHosts && uri.userInfo == null
                    "dismiss", "copy" -> true
                    else -> false
                }
                if (!allowed) { respond(request, "ACTION_NOT_ALLOWED"); return }
                actionTaken = true; recordImpression(); onEvent("action", actionId); respond(request)
                if (type == "copy") { (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Promotion", action.optString("text"))); actionTaken = false }
                else finish("action", if (type == "dismiss") null else InAppAction(type, target))
            }
            else -> respond(request, "UNKNOWN_METHOD")
        }
    }
    private fun show() {
        if (!canPresent() || !activity.hasWindowFocus() || activity.isFinishing || activity.isDestroyed) { fail("HOST_BLOCKED"); return }
        val d = Dialog(activity); dialog = d; d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = FrameLayout(activity); root.setBackgroundColor(Color.TRANSPARENT)
        val dp = activity.resources.displayMetrics.density
        val display = if (autoDismissSeconds != null) "fullscreen" else manifest.getJSONObject("display").getString("type")
        val fullscreen = display == "fullscreen"
        if (fullscreen) root.setBackgroundColor(Color.BLACK)
        val height = activity.resources.displayMetrics.heightPixels
        val layout = when (display) {
            "fullscreen" -> FrameLayout.LayoutParams(-1, -1)
            "bottom" -> FrameLayout.LayoutParams(-1, (height * .65).toInt(), Gravity.BOTTOM)
            "modal" -> FrameLayout.LayoutParams(-1, (height * .8).toInt(), Gravity.CENTER)
            else -> FrameLayout.LayoutParams(-1, -1).apply { topMargin = (56 * dp).toInt() }
        }
        root.addView(web, layout)
        if (autoDismissSeconds == null) {
            val close = Button(activity).apply { text = "✕"; contentDescription = "Close event"; setOnClickListener { finish("close_button") } }
            root.addView(close, FrameLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt(), Gravity.TOP or Gravity.END).apply { topMargin = (4 * dp).toInt(); marginEnd = (8 * dp).toInt() })
            root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (showHideToday) {
                val hide = Button(activity).apply { text = "Hide today"; contentDescription = "Hide until midnight (${artifact.optString("time_zone", "UTC")})"; setOnClickListener { hideToday() } }
                root.addView(hide, FrameLayout.LayoutParams((200 * dp).toInt(), (48 * dp).toInt(), Gravity.TOP or Gravity.START).apply { topMargin = (4 * dp).toInt(); marginStart = (8 * dp).toInt() })
            }
        }
        d.setContentView(root); d.setCanceledOnTouchOutside(false); d.setOnCancelListener { finish("back_button") }
        d.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(manifest.getJSONObject("display").getDouble("backdrop_opacity").toFloat()) }
        d.show(); d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (fullscreen) {
            d.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                statusBarColor = Color.TRANSPARENT; navigationBarColor = Color.TRANSPARENT
            }
            root.setOnApplyWindowInsetsListener { _, insets ->
                for (index in 1 until root.childCount) {
                    val control = root.getChildAt(index)
                    val params = control.layoutParams as FrameLayout.LayoutParams
                    params.topMargin = insets.systemWindowInsetTop + (4 * dp).toInt()
                    params.marginStart = insets.systemWindowInsetLeft + (8 * dp).toInt()
                    params.marginEnd = insets.systemWindowInsetRight + (8 * dp).toInt()
                    control.layoutParams = params
                }
                insets
            }
            root.requestApplyInsets()
        }
        autoDismissSeconds?.let { seconds -> handler.postDelayed({ if (!ended) { recordImpression(); finish("auto_dismiss") } }, (seconds * 1000).toLong()) }
        presented = true; onEvent("presented", ""); handler.removeCallbacks(timeout); handler.postDelayed(impressionTimer,1000); handler.postDelayed(expiry,290000)
    }
    private fun recordImpression() { if (presented && !ended && !impression) { impression = true; onEvent("impression", "") } }
    private fun hideToday() {
        if (!presented || ended || !showHideToday) return
        recordImpression(); onEvent("hide_today", ""); finish("hide_today")
    }
    private fun respond(id: String, error: String? = null) {
        val r = JSONObject().put("request_id", id).put("ok", error == null); if (error != null) r.put("error", JSONObject().put("code", error))
        val script = "window.__nudgeonReply($r)"; replies[id] = script; web?.evaluateJavascript(script,null)
    }
    private fun fail(code: String) { if (ended) return; onEvent("failed",code); close(); onEnd(null) }
    private fun finish(reason: String, action: InAppAction? = null) { if (ended) return; onEvent("dismiss",reason); close(); onEnd(action) }
    fun close() {
        if (ended) return; ended = true; handler.removeCallbacksAndMessages(null)
        dialog?.setOnCancelListener(null); dialog?.dismiss(); dialog = null
        web?.let { it.stopLoading(); WebViewCompat.removeWebMessageListener(it,"NudgeOnNative"); (it.parent as? ViewGroup)?.removeView(it); it.destroy() }; web = null
    }
}
