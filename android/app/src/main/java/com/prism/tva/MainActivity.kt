package com.prism.tva

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.tva.recipe.Generaliser
import com.prism.tva.recipe.Matcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Temporary control screen: type a command, then Teach or Run it, and see learned flows and runs.
 * Voice input and the recipe review screen replace this next.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 15f }
        input = EditText(this).apply { hint = "e.g. Order a Margherita pizza from Domino's on Zomato"; textSize = 15f }
        val teach = Button(this).apply { text = "Teach"; setOnClickListener { withService { it.startTeaching(cmd()) } } }
        val run = Button(this).apply { text = "Run"; setOnClickListener { withService { it.handleUtterance(cmd()) } } }
        val settings = Button(this).apply {
            text = "Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(teach); addView(run); addView(settings)
        }
        body = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 90, 36, 36)
            addView(status); addView(input); addView(buttons); addView(body)
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun cmd() = input.text.toString().trim()

    private fun withService(block: (TvaAccessibilityService) -> Unit) {
        val svc = TvaAccessibilityService.instance
        if (svc == null) {
            status.text = "The assistant service is off. Turn it on in Accessibility settings."
            return
        }
        if (cmd().isEmpty()) { input.error = "Say or type a command first"; return }
        block(svc)
    }

    private fun render() {
        val svc = TvaAccessibilityService.instance
        status.text = if (svc != null) "Assistant: ON" else "Assistant: OFF (enable \"Teachable Voice assistant\")"
        if (svc == null) { body.text = ""; return }
        val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.US)
        val sb = StringBuilder()
        sb.append("\nLEARNED FLOWS\n")
        for (r in svc.store.recipes().asReversed()) {
            val ex = Matcher.examples(r)
            sb.append("\n• ${r.optString("template")}   [${r.optString("appLabel")}]\n")
            val steps = r.getJSONArray("steps")
            for (i in 0 until steps.length()) sb.append("   ${i + 1}. ${Generaliser.describe(steps.getJSONObject(i), ex)}\n")
            val noise = r.optJSONArray("noise")
            if (noise != null && noise.length() > 0) sb.append("   (ignored ${noise.length()} stray taps)\n")
        }
        sb.append("\nRECENT RUNS\n")
        for (run in svc.store.runs(10).asReversed()) {
            sb.append("\n${fmt.format(Date(run.optLong("startedAt")))}  ${run.optString("outcome").uppercase()}  \"${run.optString("utterance")}\"")
            if (run.optString("reason").isNotEmpty()) sb.append("\n   ${run.optString("reason")}")
            sb.append("\n")
        }
        body.text = sb.toString()
        body.visibility = View.VISIBLE
    }
}
