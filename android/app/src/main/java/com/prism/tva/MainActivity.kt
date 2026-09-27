package com.prism.tva

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.prism.tva.llm.Fireworks
import com.prism.tva.recipe.Generaliser
import com.prism.tva.recipe.Matcher
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen: speak a command (run a learned task, or teach a new one), review what was learned,
 * and see recent runs.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var input: EditText
    private lateinit var speakBtn: Button
    private lateinit var teachBtn: Button
    private lateinit var list: LinearLayout
    private var recognizer: SpeechRecognizer? = null
    private val dp get() = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = dp
        status = TextView(this).apply { textSize = 13f; setTextColor(0xFF5F6368.toInt()) }
        heard = TextView(this).apply { textSize = 16f; setPadding(0, d(8f), 0, d(8f)) }
        speakBtn = bigButton("🎤  Tap and speak a command", 0xFF1F6FEB.toInt()) { listen(teach = false) }
        teachBtn = bigButton("＋  Teach a new task", 0xFF2DA44E.toInt()) { listen(teach = true) }
        input = EditText(this).apply { hint = "…or type a command"; textSize = 15f; setSingleLine() }
        val typed = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply { text = "Run"; setOnClickListener { withService { it.handleUtterance(cmd()) } } })
            addView(Button(this@MainActivity).apply { text = "Teach"; setOnClickListener { withService { it.startTeaching(cmd()) } } })
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "Teachable Voice"; textSize = 24f; setTypeface(typeface, Typeface.BOLD)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(18f), d(28f), d(18f), d(24f))
            addView(title); addView(status); addView(speakBtn); addView(teachBtn); addView(heard); addView(typed); addView(list)
            addView(keySettings())
        }
        setContentView(ScrollView(this).apply { addView(col) })
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    private fun bigButton(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 17f
        setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(color) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56f)).apply { topMargin = dp(12f) }
        setOnClickListener { onClick() }
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

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    private fun cmd() = input.text.toString().trim()

    private fun withService(block: (TvaAccessibilityService) -> Unit) {
        val svc = TvaAccessibilityService.instance
        if (svc == null) {
            status.text = "The assistant is off. Turn on \"Teachable Voice assistant\" in Accessibility settings."
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (cmd().isEmpty()) { input.error = "Say or type a command first"; return }
        block(svc)
    }

    /** Listens for one spoken command, then runs it (or starts teaching it). */
    private fun listen(teach: Boolean) {
        val svc = TvaAccessibilityService.instance
        if (svc == null) { withService { }; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        val btn = if (teach) teachBtn else speakBtn
        val original = btn.text
        btn.text = if (teach) "🎤  Say the task you'll show me…" else "🎤  Listening…"
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                btn.text = original
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) { heard.text = "I didn't catch that. Try again."; return }
                heard.text = "“$text”"
                input.setText(text)
                if (teach) svc.startTeaching(text) else svc.handleUtterance(text)
            }
            override fun onError(error: Int) { btn.text = original; heard.text = "I didn't catch that (error $error). Try again." }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard.text = "“$it…”" }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    private fun render() {
        val svc = TvaAccessibilityService.instance
        status.text = (if (svc != null) "Assistant on" else "Assistant off — enable it in Accessibility settings") +
            (if (Fireworks.available) " · language model ready" else " · no language model key") +
            (svc?.lastStatus?.takeIf { it.isNotBlank() }?.let { "\nLast: $it" } ?: "")
        list.removeAllViews()
        if (svc == null) return
        val d = dp
        val justLearned = intent?.getStringExtra("recipe")
        list.addView(section("Learned tasks"))
        val recipes = svc.store.recipes().filter { !it.optString("id").startsWith("r_guard") }.asReversed()
            .sortedByDescending { it.optString("id") == justLearned }
        if (recipes.isEmpty()) list.addView(note("Nothing yet. Tap “Teach a new task”, say the task, then do it once."))
        for (r in recipes) list.addView(recipeCard(r, r.optString("id") == justLearned))
        list.addView(section("Recent runs"))
        val fmt = SimpleDateFormat("dd MMM, h:mm a", Locale.US)
        val runs = svc.store.runs(8).asReversed()
        if (runs.isEmpty()) list.addView(note("No runs yet."))
        for (run in runs) {
            val outcome = run.optString("outcome")
            val color = when (outcome) { "success" -> 0xFF2DA44E; "handover" -> 0xFF1F6FEB; else -> 0xFFCF222E }.toInt()
            list.addView(TextView(this).apply {
                textSize = 13f
                setPadding(0, d(6f), 0, d(6f))
                text = "${fmt.format(Date(run.optLong("startedAt")))} · ${outcome.uppercase()} · ${run.optLong("ms") / 1000}s\n" +
                    "“${run.optString("utterance")}”" +
                    (run.optString("reason").takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
                setTextColor(color)
            })
        }
    }

    private fun recipeCard(r: JSONObject, highlight: Boolean): View {
        val d = dp
        val ex = Matcher.examples(r)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(14f), d(12f), d(14f), d(12f))
            background = GradientDrawable().apply {
                cornerRadius = d(12f).toFloat()
                setColor(if (highlight) 0xFFE8F5E9.toInt() else 0xFFF6F8FA.toInt())
                setStroke(d(1f), if (highlight) 0xFF2DA44E.toInt() else 0xFFD0D7DE.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = d(10f) }
        }
        card.addView(TextView(this).apply {
            text = (if (highlight) "Just learned · " else "") + r.optString("description").ifEmpty { r.optString("template") }
            textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        })
        val slotText = ex.entries.joinToString("   ") { "{${it.key}} = ${it.value.ifEmpty { "(as shown)" }}" }
        card.addView(TextView(this).apply { text = "${r.optString("appLabel")} · $slotText"; textSize = 12f; setTextColor(0xFF5F6368.toInt()) })
        val steps = r.getJSONArray("steps")
        val sb = StringBuilder()
        for (i in 0 until steps.length()) {
            val st = steps.getJSONObject(i)
            val line = st.optString("intent").ifEmpty { Generaliser.describe(st, ex) }
            sb.append("${i + 1}. $line").append(if (st.optBoolean("noise")) "  (ignored: not needed)" else "").append('\n')
        }
        val goals = r.optJSONArray("goals")
        if (goals != null) for (g in 0 until goals.length()) {
            sb.append("• When asked: ").append(goals.getJSONObject(g).optString("instruction")).append('\n')
        }
        val noise = r.optJSONArray("noise")?.length() ?: 0
        if (noise > 0) sb.append("Ignored $noise stray tap(s) outside the app.\n")
        card.addView(TextView(this).apply { text = sb.toString().trimEnd(); textSize = 13f; setPadding(0, d(6f), 0, d(6f)) })
        card.addView(Button(this).apply {
            text = "Delete"; isAllCaps = false
            setOnClickListener { TvaAccessibilityService.instance?.store?.deleteRecipe(r.getString("id")); render() }
        })
        return card
    }

    /** Optional: use a different Fireworks key than the one built into the app. */
    private fun keySettings(): View {
        val prefs = getSharedPreferences("tva", MODE_PRIVATE)
        val field = EditText(this).apply {
            hint = "Paste a Fireworks API key (optional)"
            textSize = 13f
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (!prefs.getString("fw_key", null).isNullOrBlank()) setText(prefs.getString("fw_key", ""))
        }
        val save = Button(this).apply {
            text = "Save"; isAllCaps = false
            setOnClickListener {
                val k = field.text.toString().trim()
                prefs.edit().putString("fw_key", k).apply()
                Fireworks.key = k.ifEmpty { BuildConfig.FW_KEY }
                render()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(section("Language model"))
            addView(note(if (BuildConfig.FW_KEY.isNotBlank()) "A key is built into this app. Paste another one to use it instead; leave empty to use the built-in key."
                else "No key is built in. Paste a Fireworks API key to enable paraphrases, pop-up handling and questions."))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(save)
            })
        }
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(20f), 0, dp(2f)); gravity = Gravity.START
    }

    private fun note(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(0xFF5F6368.toInt()) }
}
