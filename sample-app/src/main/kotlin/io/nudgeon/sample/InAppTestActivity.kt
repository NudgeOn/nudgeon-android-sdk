package io.nudgeon.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.nudgeon.inapp.InAppTestClient

/** Standalone sample flow: launched from the sample app's explicit test button. */
class InAppTestActivity : Activity() {
    private lateinit var client: InAppTestClient
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,64,32,32) }
        val title = TextView(this).apply { text = "NudgeOn in-app test\nConnect to the web source workbench"; textSize = 22f }
        val logs = TextView(this).apply { textSize = 13f }
        client = InAppTestClient(application,
            InAppTestClient.Configuration(BuildConfig.NUDGEON_API_HOST, BuildConfig.NUDGEON_SDK_KEY,
                allowedSchemes = setOf("nudgeon-sample"), allowedWebHosts = setOf("nudgeon.io")),
            host = { this }, isAllowed = { !isFinishing && !isDestroyed },
            onAction = { logs.append("\nValidated action: ${it.type} ${it.url}") },
            onDiagnostic = { logs.append("\n$it") })
        layout.addView(title)
        layout.addView(Button(this).apply { text = "Connect test device"; setOnClickListener { client.showConnection(this@InAppTestActivity) } })
        layout.addView(Button(this).apply { text = "End test session"; setOnClickListener { client.end() } })
        layout.addView(logs); setContentView(layout)
    }
    override fun onDestroy() { client.destroy(); super.onDestroy() }
}
