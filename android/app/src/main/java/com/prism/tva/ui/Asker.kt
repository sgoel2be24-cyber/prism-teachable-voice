package com.prism.tva.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.prism.tva.TvaAccessibilityService
import com.prism.tva.core.Dbg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Human in the loop during a run: speaks one question, listens for the spoken answer (or takes a typed
 * one from the app, or a dev answer over adb), and returns it. Returns null if nobody answers.
 */
class Asker(private val svc: TvaAccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pending: CompletableDeferred<String?>? = null
    private var recognizer: SpeechRecognizer? = null

    val waiting get() = pending?.isActive == true

    suspend fun ask(question: String, timeoutMs: Long = 30000): String? {
        val d = CompletableDeferred<String?>()
        pending = d
        Dbg.log("ASK $question")
        svc.hud.show("❓ $question", listOf("Skip" to { answer(null) }))
        svc.speaker.say(question) { main.post { listen(retries = 6) } }
        // Cleared even when the run is stopped mid-question, or the next command would be taken as
        // this question's answer.
        val r = try {
            withTimeoutOrNull(timeoutMs) { d.await() }
        } finally {
            if (pending === d) pending = null
            d.cancel()
            main.post { stopListening() }
        }
        Dbg.log("ANSWER ${r ?: "(none)"}")
        return r?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun answer(text: String?) {
        // "Stop" / "cancel" said instead of an answer ends the run; it isn't a value to type.
        if (text != null && STOP.containsMatchIn(com.prism.tva.core.Text.norm(text))) {
            Dbg.log("ANSWER is a stop: \"$text\"")
            pending?.complete(null)
            svc.executor.cancel()
            return
        }
        pending?.complete(text)
    }

    private val STOP = Regex("^(stop|cancel|never ?mind|forget it|leave it|rehne do|ruko|band karo)\\b")

    private fun listen(retries: Int) {
        if (!waiting) return
        if (svc.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isRecognitionAvailable(svc)
        ) {
            Dbg.log("ASK no microphone permission or recognizer; waiting for a typed answer")
            return
        }
        stopListening()
        val r = SpeechRecognizer.createSpeechRecognizer(svc)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!best.isNullOrBlank()) answer(best) else retry()
            }
            override fun onError(error: Int) {
                Dbg.log("ASK recognizer error $error")
                retry()
            }
            // Silence or an unclear answer: keep listening until the question times out (or Skip).
            private fun retry() {
                if (retries > 0 && waiting) {
                    svc.hud.update("🎤 Didn't catch that. Say it again, or tap Skip")
                    main.postDelayed({ listen(retries - 1) }, 400)
                }
            }
            override fun onReadyForSpeech(params: Bundle?) { svc.hud.update("🎤 Listening…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    /**
     * One spoken command from the status pill's "🎤 Next", without going back to the app. What it
     * hears so far is shown in the pill, so a misheard command can be cancelled before it runs.
     */
    fun listenForCommand(onText: (String) -> Unit) {
        if (svc.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !SpeechRecognizer.isRecognitionAvailable(svc)
        ) {
            svc.hud.show("Open Teachable Voice to speak a command.", listOf("OK" to { svc.hud.hide() }), autoHideMs = 6000)
            return
        }
        stopListening()
        val r = SpeechRecognizer.createSpeechRecognizer(svc)
        recognizer = r
        fun again() = svc.hud.show("I didn't catch that.",
            listOf("🎤 Again" to { listenForCommand(onText) }, "OK" to { svc.hud.hide() }), autoHideMs = 10000)
        svc.hud.show("🎤 Listening… say a command", listOf("Cancel" to { main.post { stopListening() }; svc.hud.hide() }))
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                main.post { stopListening() }
                if (best.isNullOrBlank()) { again(); return }
                Dbg.log("COMMAND from the pill: \"$best\"")
                svc.hud.show("“$best”", autoHideMs = 3000)
                onText(best)
            }
            override fun onError(error: Int) {
                Dbg.log("COMMAND recognizer error $error")
                main.post { stopListening() }
                again()
            }
            override fun onReadyForSpeech(params: Bundle?) { svc.hud.update("🎤 Listening… say a command") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { svc.hud.update("🎤 “$it…”") }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    private fun stopListening() {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }
}
