package com.prism.tva.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import com.prism.tva.core.Dbg
import java.util.Locale

/** Spoken replies through the phone's text-to-speech engine. */
class Speaker(ctx: Context) {
    private var ready = false
    private val pending = ArrayList<String>()
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale("en", "IN")
            pending.forEach { say(it) }
            pending.clear()
        }
    }

    fun say(text: String) {
        Dbg.log("SAY $text")
        if (!ready) { pending.add(text); return }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tva-" + System.nanoTime())
    }

    fun shutdown() = runCatching { tts.shutdown() }
}
