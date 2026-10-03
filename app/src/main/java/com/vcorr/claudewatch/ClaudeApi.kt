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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
            "follow-up question. You can search the web: do so when the answer depends on current " +
            "information such as news, weather, prices, opening hours or recent events, and then give " +
            "the answer itself rather than describing the search. Don't read out web addresses."

    // A few searches cover a spoken question; the cap bounds the cost of one reply.
    private const val MAX_SEARCHES = 3

    /** One question with no history, used by the voice test. */
    suspend fun ask(prompt: String, apiKey: String): String = reply(listOf(Turn(Role.USER, prompt)), apiKey).text

    /** Sends the conversation and returns Claude's reply, ready to show and speak. */
    suspend fun reply(history: List<Turn>, apiKey: String): Reply = withContext(Dispatchers.IO) {
        try {
            request(history, apiKey, withSearch = true)
        } catch (e: SearchUnavailableException) {
            // If this account or model can't use web search, answer without it rather than fail.
            request(history, apiKey, withSearch = false)
        }
    }

    private fun request(history: List<Turn>, apiKey: String, withSearch: Boolean): Reply {
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
            .put("system", "$SYSTEM_PROMPT ${now()}")
            .put("messages", messages)
        if (withSearch) {
            val search = JSONObject()
                .put("type", "web_search_20250305")
                .put("name", "web_search")
                .put("max_uses", MAX_SEARCHES)
                // The time zone alone localises results (weather, opening hours) without a location permission.
                .put("user_location", JSONObject().put("type", "approximate").put("timezone", TimeZone.getDefault().id))
            body.put("tools", JSONArray().put(search))
        }

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            doOutput = true
            connectTimeout = 15_000
            // Searching adds a few seconds or more.
            readTimeout = 60_000
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        if (conn.responseCode == 401) throw InvalidApiKeyException()

        if (conn.responseCode != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
            val message = errorMessage(err, conn.responseCode)
            if (withSearch && conn.responseCode == 400 && message.contains("web_search")) throw SearchUnavailableException()
            error(message)
        }

        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val stopReason = json.optString("stop_reason")
        if (stopReason == "refusal") return Reply("Sorry, I can't help with that one.", emptyList())

        // With search, the reply is the text after the last search result; earlier text is Claude
        // narrating the search ("I'll look that up"). A response may also begin with a non-text block.
        val content = json.getJSONArray("content")
        val blocks = (0 until content.length()).map { content.getJSONObject(it) }
        val lastResult = blocks.indexOfLast { it.optString("type") == "web_search_tool_result" }
        val texts = blocks.filter { it.optString("type") == "text" }
        // If nothing follows the last search (a paused turn), all the text is better than none.
        val answer = blocks.drop(lastResult + 1).filter { it.optString("type") == "text" }.ifEmpty { texts }
        val text = answer.joinToString("") { it.optString("text") }.trim()

        // Where the answer came from, shown under the reply.
        val sources = answer
            .flatMap { block ->
                val citations = block.optJSONArray("citations") ?: JSONArray()
                (0 until citations.length()).mapNotNull { citations.optJSONObject(it)?.optString("url") }
            }
            .mapNotNull { url -> runCatching { URL(url).host.removePrefix("www.") }.getOrNull() }
            .distinct()

        val result = if (stopReason == "max_tokens") SpokenText.upToLastSentence(text) else text
        val searches = json.optJSONObject("usage")?.optJSONObject("server_tool_use")?.optInt("web_search_requests") ?: 0
        // Counts only, never content, to check replies stay short and searches stay few.
        Log.d(TAG, "reply chars=${result.length} stop=$stopReason searches=$searches")
        return Reply(result, sources)
    }

    /** Today's date and time where the wearer is, which Claude can't otherwise know. */
    private fun now(): String {
        val format = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.UK)
        return "It is now ${format.format(Date())} in the ${TimeZone.getDefault().id} time zone."
    }

    private fun errorMessage(body: String, code: Int): String = try {
        JSONObject(body).getJSONObject("error").getString("message")
    } catch (e: Exception) {
        "HTTP $code"
    }
}

/** Claude's reply, and the websites it cited, if it searched. */
data class Reply(val text: String, val sources: List<String>) {

    /** The reply as shown on screen: the text, then where it came from. */
    val display: String
        get() = if (sources.isEmpty()) text else "$text\n\nSources: ${sources.joinToString(", ")}"
}

/** The request was refused because of the web search tool, so it is retried without it. */
private class SearchUnavailableException : Exception("web search unavailable")

/** The API rejected the key (HTTP 401). */
class InvalidApiKeyException : Exception("API key rejected")
