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
        svc.speaker.say(question) { main.post { listen(retries = 1) } }
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        pending = null
        main.post { stopListening() }
        Dbg.log("ANSWER ${r ?: "(none)"}")
        return r?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun answer(text: String?) {
        pending?.complete(text)
    }

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
                if (best.isNullOrBlank() && retries > 0) listen(retries - 1) else answer(best)
            }
            override fun onError(error: Int) {
                Dbg.log("ASK recognizer error $error")
                if (retries > 0 && waiting) main.postDelayed({ listen(retries - 1) }, 300)
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

    private fun stopListening() {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }
}
