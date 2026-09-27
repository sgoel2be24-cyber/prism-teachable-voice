package com.prism.tva.recipe

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * On-device storage: learned recipes, raw teach recordings (with per-tap screen snapshots) and the
 * run log. Lives in the app's external files dir so it survives restarts and can be inspected over adb.
 */
class Store(ctx: Context) {
    val root: File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    private val recipesDir = File(root, "recipes").apply { mkdirs() }
    private val recordingsDir = File(root, "recordings").apply { mkdirs() }
    private val runsFile = File(root, "runs.jsonl")

    fun snapDir(recId: String) = File(recordingsDir, recId).apply { mkdirs() }

    fun saveRecording(id: String, json: JSONObject) = File(recordingsDir, "$id.json").writeText(json.toString(1))

    fun recording(id: String): JSONObject? =
        File(recordingsDir, "$id.json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    @Synchronized
    fun saveRecipe(r: JSONObject) = File(recipesDir, r.getString("id") + ".json").writeText(r.toString(1))

    fun recipes(): List<JSONObject> =
        recipesDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .sortedBy { it.lastModified() }
            .mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }

    fun recipe(id: String): JSONObject? =
        File(recipesDir, "$id.json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    fun deleteRecipe(id: String) = File(recipesDir, "$id.json").delete()

    @Synchronized
    fun appendRun(r: JSONObject) = runsFile.appendText(r.toString() + "\n")

    fun runs(limit: Int = 50): List<JSONObject> =
        if (!runsFile.exists()) emptyList()
        else runsFile.readLines().takeLast(limit).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
}
