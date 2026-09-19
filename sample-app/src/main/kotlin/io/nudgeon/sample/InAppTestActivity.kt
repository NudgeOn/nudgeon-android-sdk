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
        val transfer = TextView(this).apply { textSize = 16f }
        val logs = TextView(this).apply { textSize = 13f }
        client = InAppTestClient(application,
            InAppTestClient.Configuration(BuildConfig.NUDGEON_API_HOST, BuildConfig.NUDGEON_SDK_KEY,
                allowedSchemes = setOf("nudgeon-sample"), allowedWebHosts = setOf("nudgeon.io")),
            host = { this }, isAllowed = { !isFinishing && !isDestroyed },
            onAction = { logs.append("\nValidated action: ${it.type} ${it.url}") },
            onDiagnostic = { logs.append("\n$it") },
            onTransferStatus = { transfer.text = "${it.phase} · pending ${it.pendingCount} · received ${it.acknowledgedCount}\n${it.reason ?: ""}" })
        layout.addView(title)
        layout.addView(Button(this).apply { text = "Connect test device"; setOnClickListener { client.showConnection(this@InAppTestActivity) } })
        layout.addView(Button(this).apply { text = "End test session"; setOnClickListener { client.end() } })
        layout.addView(transfer)
        layout.addView(Button(this).apply { text = "Retry transfer"; setOnClickListener { client.retryPendingEvents() } })
        layout.addView(Button(this).apply { text = "Discard pending records"; setOnClickListener {
            android.app.AlertDialog.Builder(this@InAppTestActivity).setTitle("Discard review records?")
                .setMessage("Pending records will be lost. This does not approve the review.")
                .setNegativeButton("Keep records", null).setPositiveButton("Discard") { _, _ -> client.discardPendingEvents() }.show()
        } })
        layout.addView(logs); setContentView(android.widget.ScrollView(this).apply { addView(layout) })
    }
    override fun onDestroy() { client.destroy(); super.onDestroy() }
}
