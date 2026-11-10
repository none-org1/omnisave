package com.omnisave.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/* Gemini enrichment (optional). Same prompt/JSON contract as the extension SW. */
object GemClient {
    suspend fun enrich(ctx: Context, item: Item): Item? = withContext(Dispatchers.IO) {
        try {
            val g = ctx.getSharedPreferences("omnisave", Context.MODE_PRIVATE)
            if (!g.getBoolean("gemOn", false)) return@withContext null
            val key = g.getString("gemKey", "") ?: return@withContext null
            val model = g.getString("gemModel", "gemini-2.0-flash")
            val prompt = "You label saved content for a personal library. Reply with JSON ONLY: {\"tags\":[\"up to 3 short lowercase tags\"],\"summary\":\"one line, max 90 chars\"}\n" +
                "Title: ${item.title}\nURL: ${item.url}\nUser note: ${item.refText.ifBlank { "(none)" }}\nPreview: ${(item.preview ?: "").take(400)}"
            val body = JSONObject().put("contents", org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 120))
            val c = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${key}").openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"; setRequestProperty("content-type", "application/json"); doOutput = true; connectTimeout = 8000; readTimeout = 12000
            }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val text = c.inputStream.bufferedReader().readText()
            if (c.responseCode != 200) return@withContext null
            val js = JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            var s = ""; for (i in 0 until js.length()) s += js.getJSONObject(i).getString("text")
            val out = JSONObject(s.substring(s.indexOf('{'), s.lastIndexOf('}') + 1))
            val tags = out.optJSONArray("tags")?.let { arr -> (0 until arr.length()).take(3).map { arr.getString(it).lowercase().take(24) } }
            item.copy(tags = tags?.filter { it.isNotBlank() }?.joinToString(",") ?: item.tags,
                      summary = out.optString("summary", "").take(160).ifBlank { null })
        } catch (e: Exception) { null }
    }

    fun persist(ctx: Context, on: Boolean, key: String, model: String) =
        ctx.getSharedPreferences("omnisave", Context.MODE_PRIVATE)
            .edit().putBoolean("gemOn", on).putString("gemKey", key).putString("gemModel", model.ifBlank { "gemini-2.0-flash" }).apply()
}
