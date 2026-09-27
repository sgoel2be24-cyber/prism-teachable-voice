package com.prism.tva

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Spike launcher: shows whether the recorder service is on and where it writes its log. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 16f }
        val openSettings = Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
            addView(status)
            addView(openSettings)
        }
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true
        status.text = buildString {
            appendLine("Teach Spike (development build)")
            appendLine()
            appendLine("Recorder service: " + if (enabled) "ON" else "OFF")
            appendLine("Log: ${getExternalFilesDir(null)}/events.jsonl")
        }
    }
}
