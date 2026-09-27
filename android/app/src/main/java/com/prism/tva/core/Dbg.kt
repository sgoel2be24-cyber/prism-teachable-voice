package com.prism.tva.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Development log: logcat plus a file the Mac can pull over adb. */
object Dbg {
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "debug.log")
    }

    @Synchronized
    fun log(msg: String) {
        Log.d("TVA", msg.take(3500))
        runCatching { file?.appendText(fmt.format(Date()) + " " + msg + "\n") }
    }
}
