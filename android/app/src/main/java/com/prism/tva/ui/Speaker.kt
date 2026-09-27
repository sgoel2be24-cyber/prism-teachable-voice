package com.prism.tva.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.prism.tva.core.Dbg
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Spoken replies through the phone's text-to-speech engine. */
class Speaker(ctx: Context) {
    private var ready = false
    private val pending = ArrayList<Pair<String, (() -> Unit)?>>()
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale("en", "IN")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { id?.let { callbacks.remove(it)?.invoke() } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { id?.let { callbacks.remove(it)?.invoke() } }
            })
            pending.forEach { (t, cb) -> say(t, cb) }
            pending.clear()
        }
    }

    /** Speaks [text]; [onDone] runs when it has finished (or immediately if speech is unavailable). */
    fun say(text: String, onDone: (() -> Unit)? = null) {
        Dbg.log("SAY $text")
        if (!ready) { pending.add(text to onDone); return }
        val id = "tva-" + System.nanoTime()
        if (onDone != null) callbacks[id] = onDone
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) callbacks.remove(id)?.invoke()
    }

    fun shutdown() = runCatching { tts.shutdown() }
}
