package com.prism.tva.llm

import com.prism.tva.BuildConfig
import com.prism.tva.core.Dbg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal OpenAI-compatible client for Fireworks. Returns null on any failure so callers fall back. */
object Fireworks {
    /** Fast model for anything on the replay path (about 2 s per call at low reasoning effort). */
    const val FAST = "accounts/fireworks/models/gpt-oss-120b"
    private const val URL_CHAT = "https://api.fireworks.ai/inference/v1/chat/completions"

    @Volatile var key: String = BuildConfig.FW_KEY
    val available get() = key.isNotBlank()

    suspend fun chat(
        system: String,
        user: String,
        model: String = FAST,
        effort: String? = "low",
        maxTokens: Int = 700,
        timeoutMs: Int = 20000,
        tag: String = "llm",
    ): String? = withContext(Dispatchers.IO) {
        if (!available) return@withContext null
        val t0 = System.currentTimeMillis()
        try {
            val body = JSONObject()
                .put("model", model).put("max_tokens", maxTokens).put("temperature", 0)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
            if (effort != null) body.put("reasoning_effort", effort)
            val conn = (URL(URL_CHAT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Dbg.log("LLM[$tag] HTTP $code ${text.take(300)}")
                return@withContext null
            }
            val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
            Dbg.log("LLM[$tag] ${System.currentTimeMillis() - t0}ms -> ${content.replace('\n', ' ').take(400)}")
            content
        } catch (e: Exception) {
            Dbg.log("LLM[$tag] failed after ${System.currentTimeMillis() - t0}ms: $e")
            null
        }
    }

    /** Like [chat] but parses the first JSON object in the reply. */
    suspend fun json(system: String, user: String, tag: String, effort: String? = "low", maxTokens: Int = 700, timeoutMs: Int = 20000): JSONObject? {
        val s = chat(system, user, effort = effort, maxTokens = maxTokens, timeoutMs = timeoutMs, tag = tag) ?: return null
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { JSONObject(s.substring(a, b + 1)) }.getOrNull()
    }
}
