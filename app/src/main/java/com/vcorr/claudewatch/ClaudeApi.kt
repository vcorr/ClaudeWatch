package com.vcorr.claudewatch

import android.util.Log
import com.vcorr.claudewatch.core.Role
import com.vcorr.claudewatch.core.SpokenText
import com.vcorr.claudewatch.core.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ClaudeApi {

    private const val TAG = "ClaudeWatch"
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-haiku-4-5"

    // Brevity comes from the prompt; the cap only stops runaway replies.
    private const val MAX_TOKENS = 1024

    private const val SYSTEM_PROMPT =
        "You are Claude, talking with someone through their smartwatch. Your replies are read aloud " +
            "by text-to-speech and shown on a small screen, so answer in plain spoken sentences: no " +
            "markdown, lists, headings, code blocks, emoji or URLs. Keep replies short, usually one to " +
            "three sentences, unless they ask for more detail. If a request is unclear, ask a brief " +
            "follow-up question."

    /** One question with no history, used by the voice test. */
    suspend fun ask(prompt: String, apiKey: String): String = reply(listOf(Turn(Role.USER, prompt)), apiKey)

    /** Sends the conversation and returns Claude's reply, ready to show and speak. */
    suspend fun reply(history: List<Turn>, apiKey: String): String = withContext(Dispatchers.IO) {
        val messages = JSONArray()
        history.forEach { turn ->
            messages.put(
                JSONObject()
                    .put("role", if (turn.role == Role.USER) "user" else "assistant")
                    .put("content", turn.text)
            )
        }
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", MAX_TOKENS)
            .put("system", SYSTEM_PROMPT)
            .put("messages", messages)
            .toString()

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body) }

        if (conn.responseCode == 401) throw InvalidApiKeyException()

        if (conn.responseCode != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
            error(errorMessage(err, conn.responseCode))
        }

        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val stopReason = json.optString("stop_reason")
        if (stopReason == "refusal") return@withContext "Sorry, I can't help with that one."

        // A response may begin with a non-text block, so take the first text block, not content[0].
        val content = json.getJSONArray("content")
        val text = (0 until content.length())
            .map { content.getJSONObject(it) }
            .firstOrNull { it.optString("type") == "text" }
            ?.optString("text")
            .orEmpty()
            .trim()

        val result = if (stopReason == "max_tokens") SpokenText.upToLastSentence(text) else text
        // Length only, never content, to check replies stay short.
        Log.d(TAG, "reply chars=${result.length} stop=$stopReason")
        result
    }

    private fun errorMessage(body: String, code: Int): String = try {
        JSONObject(body).getJSONObject("error").getString("message")
    } catch (e: Exception) {
        "HTTP $code"
    }
}

/** The API rejected the key (HTTP 401). */
class InvalidApiKeyException : Exception("API key rejected")
