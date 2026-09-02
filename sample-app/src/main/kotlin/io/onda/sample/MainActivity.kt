package io.onda.sample

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import io.onda.sdk.Onda
import io.onda.sdk.PushPayload
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var tokenText: TextView
    private lateinit var externalIdInput: EditText
    private var openedToken: UUID? = null
    private var receivedToken: UUID? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appendLog("알림 권한 선택 완료: ${if (granted) "허용" else "거부"}")
            syncSdkPermissionState(granted)
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        logText = findViewById(R.id.log_text)
        tokenText = findViewById(R.id.token_text)
        externalIdInput = findViewById(R.id.external_id)

        bindActions()
        registerPushListeners()
        refreshStatus()

        if (savedInstanceState == null) {
            handleLaunchIntent(intent)
            // 실기기 첫 FCM 테스트: Firebase가 설정돼 있으면 시작 시 토큰을 바로 조회·표시한다.
            if (BuildConfig.HAS_GOOGLE_SERVICES) syncCurrentFcmToken()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onDestroy() {
        openedToken?.let(Onda::off)
        receivedToken?.let(Onda::off)
        super.onDestroy()
    }

    private fun bindActions() {
        findViewById<Button>(R.id.identify_button).setOnClickListener {
            val externalId = externalIdInput.text.toString().trim()
            if (externalId.isEmpty()) {
                appendLog("external_id를 입력하세요")
            } else {
                Onda.identify(externalId)
                appendLog("identify 비동기 요청: $externalId — 직후 track 귀속 완료를 보장하지 않습니다")
            }
        }
        findViewById<Button>(R.id.attributes_button).setOnClickListener {
            Onda.setUserAttributes(mapOf("plan" to "sample", "locale" to "ko-KR"))
            appendLog("사용자 속성 비동기 요청")
        }
        findViewById<Button>(R.id.track_button).setOnClickListener {
            Onda.track("sample_button_tapped", mapOf("screen" to "main", "source" to "sample-app"))
            appendLog("sample_button_tapped 이벤트 큐 등록")
        }
        findViewById<Button>(R.id.flush_button).setOnClickListener {
            Onda.flush()
            appendLog("flush 요청")
        }
        findViewById<Button>(R.id.reset_button).setOnClickListener {
            Onda.reset()
            appendLog("reset 비동기 요청 — 서버 토큰 분리까지 완료됐다는 의미는 아닙니다")
        }
        findViewById<Button>(R.id.permission_button).setOnClickListener { requestNotificationPermission() }
        findViewById<Button>(R.id.token_button).setOnClickListener { syncCurrentFcmToken() }
        findViewById<Button>(R.id.refresh_button).setOnClickListener { refreshStatus() }
        findViewById<Button>(R.id.opt_in_button).setOnClickListener {
            Onda.setPushSubscription(true)
            appendLog("서비스 푸시 수신 동의 비동기 요청: true (현재 SDK 로컬 상태)")
        }
        findViewById<Button>(R.id.opt_out_button).setOnClickListener {
            Onda.setPushSubscription(false)
            appendLog("서비스 푸시 수신 동의 비동기 요청: false (현재 SDK 로컬 상태)")
        }
    }

    private fun registerPushListeners() {
        openedToken = Onda.onPushOpened { payload -> showOpenedPush(payload, "pushOpened 리스너") }
        receivedToken = Onda.onPushReceived { payload ->
            appendLog(
                "푸시 수신: message_id=${payload.messageId} title=${payload.title} body=${payload.body}" +
                    (payload.deepLink?.let { " deep_link=$it" } ?: ""),
            )
            refreshStatus()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        syncSdkPermissionState(hasNotificationPermission())
    }

    /**
     * Onda.registerForPush currently reports the state immediately after requestPermissions().
     * The sample therefore calls it only after Activity Result has delivered the user's choice.
     */
    private fun syncSdkPermissionState(granted: Boolean) {
        Onda.registerForPush(if (granted) this else null) { result ->
            appendLog("Onda 권한 상태 동기화: $result")
        }
    }

    private fun syncCurrentFcmToken() {
        if (!BuildConfig.HAS_GOOGLE_SERVICES || FirebaseApp.getApps(this).isEmpty()) {
            appendLog("FCM 미설정: sample-app/google-services.json을 추가한 뒤 다시 빌드하세요")
            return
        }
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val token = if (task.isSuccessful) task.result?.takeIf(String::isNotBlank) else null
            if (token == null) {
                appendLog("FCM 토큰 조회 실패: ${task.exception?.message ?: "unknown"}")
            } else {
                Onda.setPushToken(token)
                tokenText.text = token
                appendLog("FCM 토큰을 Onda에 전달 (${token.take(10)}…) — 서버 등록 완료 콜백은 없습니다")
                refreshStatus()
            }
        }
    }

    private fun handleLaunchIntent(intent: Intent) {
        val extras = intent.extras
        val pushData = if (extras == null) {
            emptyMap()
        } else {
            PushIntentData.from(extras.keySet().associateWith { key -> extras.getString(key) })
        }

        if (pushData.isNotEmpty() && Onda.handlePushOpened(pushData)) {
            // Consume the one-shot data so this same Intent cannot emit another $push_opened.
            pushData.keys.forEach(intent::removeExtra)
            appendLog("알림 탭을 Onda.handlePushOpened로 전달")
            return
        }

        intent.dataString?.let { routeDeepLink(it, "Android 딥링크 Intent") }
    }

    private fun showOpenedPush(payload: PushPayload, source: String) {
        appendLog("$source: message_id=${payload.messageId}")
        payload.deepLink?.let { routeDeepLink(it, source) }
    }

    private fun routeDeepLink(raw: String, source: String) {
        val destination = SampleDeepLinkRouter.destination(raw)
        if (destination == null) {
            appendLog("$source 딥링크 보류(샘플 namespace 외부): $raw")
        } else {
            appendLog("$source 딥링크 라우팅: $destination")
        }
    }

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
        }

    private fun refreshStatus() {
        val subscription = Onda.getPushSubscription()
        val firebase = if (BuildConfig.HAS_GOOGLE_SERVICES) "configured" else "missing"
        statusText.text = getString(
            R.string.status_template,
            BuildConfig.ONDA_API_HOST,
            maskedKey(BuildConfig.ONDA_SDK_KEY),
            Onda.getDeviceId() ?: "initializing",
            Onda.getAnonId() ?: "initializing",
            subscription.osPermission,
            subscription.serviceOptIn.toString(),
            subscription.tokenRegistered.toString(),
            firebase,
        )
    }

    private fun appendLog(message: String) {
        logText.append("\n• $message")
    }

    private fun maskedKey(value: String): String =
        if (value.length <= 10) value else "${value.take(6)}…${value.takeLast(4)}"
}
