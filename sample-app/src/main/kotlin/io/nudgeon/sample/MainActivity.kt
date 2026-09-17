package io.nudgeon.sample

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
import io.nudgeon.sdk.NudgeOn
import io.nudgeon.sdk.PushPayload
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

        val testButton = Button(this).apply {
            text = "In-app event test"
            setOnClickListener { startActivity(Intent(this@MainActivity, InAppTestActivity::class.java)) }
        }
        (statusText.parent as? android.view.ViewGroup)?.addView(testButton, 0)
        val campaignButton = Button(this).apply {
            text = "Live in-app campaigns"
            setOnClickListener { startActivity(Intent(this@MainActivity, InAppCampaignActivity::class.java)) }
        }
        (statusText.parent as? android.view.ViewGroup)?.addView(campaignButton, 1)
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
        openedToken?.let(NudgeOn::off)
        receivedToken?.let(NudgeOn::off)
        super.onDestroy()
    }

    private fun bindActions() {
        findViewById<Button>(R.id.identify_button).setOnClickListener {
            val externalId = externalIdInput.text.toString().trim()
            if (externalId.isEmpty()) {
                appendLog("external_id를 입력하세요")
            } else {
                NudgeOn.identify(externalId)
                appendLog("identify 비동기 요청: $externalId — 직후 track 귀속 완료를 보장하지 않습니다")
            }
        }
        findViewById<Button>(R.id.attributes_button).setOnClickListener {
            NudgeOn.setUserAttributes(mapOf("plan" to "sample", "locale" to "ko-KR"))
            appendLog("사용자 속성 비동기 요청")
        }
        findViewById<Button>(R.id.track_button).setOnClickListener {
            NudgeOn.track("sample_button_tapped", mapOf("screen" to "main", "source" to "sample-app"))
            appendLog("sample_button_tapped 이벤트 큐 등록")
        }
        findViewById<Button>(R.id.flush_button).setOnClickListener {
            NudgeOn.flush()
            appendLog("flush 요청")
        }
        findViewById<Button>(R.id.reset_button).setOnClickListener {
            NudgeOn.reset()
            appendLog("reset 비동기 요청 — 서버 토큰 분리까지 완료됐다는 의미는 아닙니다")
        }
        findViewById<Button>(R.id.permission_button).setOnClickListener { requestNotificationPermission() }
        findViewById<Button>(R.id.sdk_permission_button).setOnClickListener { requestPermissionViaSdk() }
        findViewById<Button>(R.id.token_button).setOnClickListener { syncCurrentFcmToken() }
        findViewById<Button>(R.id.refresh_button).setOnClickListener { refreshStatus() }
        findViewById<Button>(R.id.opt_in_button).setOnClickListener {
            NudgeOn.setPushSubscription(true)
            appendLog("서비스 푸시 수신 동의 비동기 요청: true (현재 SDK 로컬 상태)")
        }
        findViewById<Button>(R.id.opt_out_button).setOnClickListener {
            NudgeOn.setPushSubscription(false)
            appendLog("서비스 푸시 수신 동의 비동기 요청: false (현재 SDK 로컬 상태)")
        }
        // FCM 없이 SDK 자동 표시 경로를 그대로 태운다 (계약 data 키 그대로, message_id는 매번 새로).
        findViewById<Button>(R.id.preview_button).setOnClickListener {
            val data = mapOf(
                "message_id" to UUID.randomUUID().toString(),
                "journey_id" to "preview-journey",
                "title" to "NudgeOn 미리보기", "body" to "SDK가 직접 그린 알림입니다 — 탭하면 딥링크로 이동합니다.",
                "deep_link" to "nudgeon-sample://home",
                "image_url" to "https://picsum.photos/seed/nudgeon/600/300",
                "data" to """{"k":"v"}""",
            )
            appendLog("미리보기 handleRemoteMessage → ${NudgeOn.handleRemoteMessage(data)}")
        }
    }

    private fun registerPushListeners() {
        openedToken = NudgeOn.onPushOpened { payload -> showOpenedPush(payload, "pushOpened 리스너") }
        receivedToken = NudgeOn.onPushReceived { payload ->
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
     * The other integration style: let the SDK own the dialog. The callback arrives after the user answers
     * (the SDK detects the requesting Activity resuming behind the system dialog) and the SDK re-reports
     * os_permission for the registered token. Forwarding onRequestPermissionsResult below makes the
     * callback immediate and also covers the "don't ask again" state that shows no dialog.
     */
    private fun requestPermissionViaSdk() {
        appendLog("SDK에 권한 요청 위임 — 콜백은 사용자 응답 뒤에 옵니다")
        NudgeOn.registerForPush(this) { result ->
            appendLog("registerForPush 콜백(응답 뒤): $result")
            refreshStatus()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (NudgeOn.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            appendLog("onRequestPermissionsResult를 SDK에 전달")
        }
    }

    /**
     * The sample owns the permission dialog through Activity Result, so it calls registerForPush only after
     * the user's choice is known.
     */
    private fun syncSdkPermissionState(granted: Boolean) {
        NudgeOn.registerForPush(if (granted) this else null) { result ->
            appendLog("NudgeOn 권한 상태 동기화: $result")
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
                NudgeOn.setPushToken(token)
                tokenText.text = token
                appendLog("FCM 토큰을 NudgeOn에 전달 (${token.take(10)}…) — 서버 등록 완료 콜백은 없습니다")
                refreshStatus()
            }
        }
    }

    private fun handleLaunchIntent(intent: Intent) {
        // SDK 자동 표시 알림의 탭 — extras를 일회성으로 소비하고 $push_opened·onPushOpened 리스너로 잇는다.
        if (NudgeOn.handleLaunchIntent(intent)) {
            appendLog("알림 탭을 NudgeOn.handleLaunchIntent로 전달")
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
        val subscription = NudgeOn.getPushSubscription()
        val firebase = if (BuildConfig.HAS_GOOGLE_SERVICES) "configured" else "missing"
        statusText.text = getString(
            R.string.status_template,
            BuildConfig.NUDGEON_API_HOST,
            maskedKey(BuildConfig.NUDGEON_SDK_KEY),
            NudgeOn.getDeviceId() ?: "initializing",
            NudgeOn.getAnonId() ?: "initializing",
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
