package io.nudgeon.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.nudgeon.inapp.InAppCampaignClient
import io.nudgeon.inapp.InAppTestClient

/** Public campaigns run separately from explicit workbench test sessions. */
class InAppCampaignActivity : Activity() {
    private lateinit var client: InAppCampaignClient
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,64,32,32) }
        val logs = TextView(this).apply { textSize = 12f }
        client = InAppCampaignClient(application,
            InAppTestClient.Configuration(BuildConfig.NUDGEON_API_HOST,BuildConfig.NUDGEON_SDK_KEY,allowedWebHosts=setOf("nudgeon.io")),
            host={this},isAllowed={!isFinishing && !isDestroyed},
            onAction={logs.append("\nValidated action: ${it.type}")},onDiagnostic={logs.append("\n$it")})
        layout.addView(TextView(this).apply { text="NudgeOn live campaigns"; textSize=22f })
        layout.addView(Button(this).apply { text="Enable campaigns"; setOnClickListener { client.enable() } })
        layout.addView(Button(this).apply { text="App foreground"; setOnClickListener { client.foreground() } })
        val name = EditText(this).apply { hint="Screen or event name" }; layout.addView(name)
        layout.addView(Button(this).apply { text="Screen"; setOnClickListener { client.screen(name.text.toString()) } })
        layout.addView(Button(this).apply { text="Event"; setOnClickListener { client.track(name.text.toString()) } })
        layout.addView(Button(this).apply { text="Disable campaigns"; setOnClickListener { client.disable() } })
        layout.addView(logs);setContentView(layout)
    }
    override fun onDestroy() { client.destroy(); super.onDestroy() }
}
