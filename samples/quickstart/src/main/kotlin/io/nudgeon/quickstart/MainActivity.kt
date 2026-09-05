package io.nudgeon.quickstart

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.nudgeon.sdk.NudgeOn
import io.nudgeon.sdk.NudgeOnConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Maven Central에 게시된 `io.nudgeon:nudgeon-sdk` 를 그대로 쓰는 최소 예제다.
 * 로컬 `:nudgeon` 모듈을 참조하지 않는다 — 게시된 아티팩트가 실제로 동작하는지 확인하는 것이 목적이다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var stateText: TextView
    private lateinit var logText: TextView
    private lateinit var externalIdInput: EditText
    private val lines = ArrayDeque<String>()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // OS 권한과 서비스 수신 동의는 별개다. 사용자가 허용했을 때만 구독을 켠다.
            NudgeOn.setPushSubscription(granted)
            log(if (granted) "알림 권한 허용" else "알림 권한 거부")
            renderState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateText = findViewById(R.id.state_text)
        logText = findViewById(R.id.log_text)
        externalIdInput = findViewById(R.id.external_id)

        findViewById<TextView>(R.id.source_text).text =
            "의존성: io.nudgeon:nudgeon-sdk:0.1.0 (Maven Central)"

        // 1. 초기화 — Application.onCreate 에서 하는 것이 더 좋다. 예제라 여기서 한다.
        NudgeOn.initialize(
            this,
            NudgeOnConfig(
                sdkKey = BuildConfig.SDK_KEY,
                apiHost = BuildConfig.API_HOST,
            ),
        )
        log("초기화 완료 · host=${BuildConfig.API_HOST}")

        if (BuildConfig.SDK_KEY.startsWith("pk_replace_me")) {
            log("⚠️ placeholder 키입니다. -PnudgeonSdkKey 로 실제 키를 넘기세요")
        }

        // 2. 푸시 열림/수신 리스너. 콜드 스타트로 놓친 것도 버퍼에서 재생된다.
        NudgeOn.onPushOpened { payload -> log("푸시 열림: ${payload.title} → ${payload.deepLink}") }
        NudgeOn.onPushReceived { payload -> log("푸시 수신: ${payload.messageId}") }
        NudgeOn.getInitialPushPayload()?.let { log("콜드 스타트 푸시: ${it.messageId}") }

        bindButtons()
        renderState()
    }

    private fun bindButtons() {
        findViewById<Button>(R.id.btn_identify).setOnClickListener {
            val id = externalIdInput.text.toString().trim()
            if (id.isEmpty()) {
                log("external id 를 입력하세요")
            } else {
                NudgeOn.identify(id)
                NudgeOn.setUserAttributes(mapOf("plan" to "free", "locale" to "ko-KR"))
                log("identify($id) + 속성 2건")
                renderState()
            }
        }

        findViewById<Button>(R.id.btn_track).setOnClickListener {
            NudgeOn.track("product_viewed", mapOf("product_id" to "P-1", "price" to 12900))
            log("track(product_viewed)")
        }

        findViewById<Button>(R.id.btn_permission).setOnClickListener { requestNotificationPermission() }

        findViewById<Button>(R.id.btn_flush).setOnClickListener {
            NudgeOn.flush()
            log("flush() — 큐를 즉시 전송")
        }

        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            NudgeOn.reset()
            // reset() 은 로컬 식별자·토큰 캐시만 정리한다. 서버 로그아웃 완료를 뜻하지 않는다.
            log("reset() — 로컬 상태만 초기화됨")
            renderState()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            NudgeOn.setPushSubscription(true)
            log("Android 12 이하 — 런타임 권한 불필요")
            renderState()
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            NudgeOn.setPushSubscription(true)
            log("이미 허용된 상태")
            renderState()
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun renderState() {
        val sub = NudgeOn.getPushSubscription()
        stateText.text = buildString {
            appendLine("device id : ${NudgeOn.getDeviceId() ?: "-"}")
            appendLine("anon id   : ${NudgeOn.getAnonId() ?: "-"}")
            appendLine("수신 동의  : ${sub.serviceOptIn}")
            appendLine("OS 권한   : ${sub.osPermission}")
            append("토큰 등록  : ${sub.tokenRegistered}")
        }
    }

    private fun log(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        lines.addFirst("$ts  $message")
        while (lines.size > 30) lines.removeLast()
        logText.text = lines.joinToString("\n")
    }
}
