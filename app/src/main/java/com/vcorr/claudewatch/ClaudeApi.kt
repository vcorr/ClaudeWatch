package com.vcorr.claudewatch

import android.util.Log
import com.vcorr.claudewatch.core.Role
import com.vcorr.claudewatch.core.SpokenText
import com.vcorr.claudewatch.core.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
            "follow-up question. You have tools that read the watch: its location, the weather, its " +
            "battery and alarms, the wearer's heart rate and their calendar. Use one when the question " +
            "needs it, then give the answer itself rather than describing the tool. Use web search only " +
            "when the wearer asks you to search or look something up; otherwise answer from what you " +
            "know, and if current information would help, say so briefly and offer to look it up. " +
            "Don't read out web addresses."

    // Paid web search runs only when asked for, and once per reply at most.
    private const val MAX_SEARCHES = 1

    // Rounds of tool calls in one reply before giving up; a spoken question rarely needs more than two.
    private const val MAX_ROUNDS = 5

    /** One question with no history and no tools, used by the voice test. */
    suspend fun ask(prompt: String, apiKey: String): String =
        reply(listOf(Turn(Role.USER, prompt)), apiKey, tools = null).text

    /**
     * Sends the conversation and returns Claude's reply, ready to show and speak. When Claude asks
     * for a watch tool, it runs here and the answer goes back, until Claude replies in words.
     * [onProgress] hears what the watch is doing meanwhile, such as "Checking the weather".
     * [onText] hears Claude's words as they stream in, on a background thread, so speaking can
     * start before the reply is complete; it hears every text block, including any said before a
     * tool call ("Let me check").
     */
    suspend fun reply(
        history: List<Turn>,
        apiKey: String,
        tools: WatchTools?,
        onProgress: (String) -> Unit = {},
        onText: (String) -> Unit = {},
    ): Reply = withContext(Dispatchers.IO) {
        try {
            converse(history, apiKey, tools, withSearch = tools != null, onProgress, onText)
        } catch (e: SearchUnavailableException) {
            // If this account or model can't use web search, answer without it rather than fail.
            converse(history, apiKey, tools, withSearch = false, onProgress, onText)
        }
    }

    private suspend fun converse(
        history: List<Turn>,
        apiKey: String,
        tools: WatchTools?,
        withSearch: Boolean,
        onProgress: (String) -> Unit,
        onText: (String) -> Unit,
    ): Reply {
        val messages = JSONArray()
        history.forEach { turn ->
            messages.put(
                JSONObject()
                    .put("role", if (turn.role == Role.USER) "user" else "assistant")
                    .put("content", turn.text)
            )
        }
        val toolList = JSONArray()
        if (tools != null) {
            for (i in 0 until tools.definitions.length()) toolList.put(tools.definitions.get(i))
        }
        if (withSearch) {
            toolList.put(
                JSONObject()
                    .put("type", "web_search_20250305")
                    .put("name", "web_search")
                    .put("max_uses", MAX_SEARCHES)
                    // The time zone alone localises results without sending a location.
                    .put("user_location", JSONObject().put("type", "approximate").put("timezone", TimeZone.getDefault().id))
            )
        }

        val used = mutableSetOf<String>()
        repeat(MAX_ROUNDS) { round ->
            // Each round's words start a new sentence, never run on from the last round's.
            if (round > 0) onText(" ")
            val json = post(messages, toolList, apiKey, withSearch, onText)
            val stopReason = json.optString("stop_reason")
            val content = json.getJSONArray("content")
            when (stopReason) {
                "tool_use" -> {
                    // Claude wants the watch: send its turn back unchanged, then the tools' answers.
                    messages.put(JSONObject().put("role", "assistant").put("content", content))
                    val results = JSONArray()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if (block.optString("type") != "tool_use") continue
                        val name = block.optString("name")
                        used += name
                        onProgress(tools?.progress(name) ?: "Thinking")
                        val result = JSONObject().put("type", "tool_result").put("tool_use_id", block.optString("id"))
                        try {
                            result.put("content", tools?.run(name, block.optJSONObject("input") ?: JSONObject()) ?: "No tools available.")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val reason = if (e is WatchTools.ToolException) e.message else "it failed (${e.javaClass.simpleName})"
                            result.put("content", "Couldn't get that: $reason").put("is_error", true)
                        }
                        results.put(result)
                    }
                    messages.put(JSONObject().put("role", "user").put("content", results))
                    onProgress("Thinking")
                }
                // The server paused a long search; sending its turn back lets it carry on.
                "pause_turn" -> messages.put(JSONObject().put("role", "assistant").put("content", content))
                else -> return finish(json, used)
            }
        }
        return Reply("Sorry, that took too many steps. Could you ask it more simply?", emptyList(), refused = true)
    }

    /**
     * One streamed request. Text is passed to [onText] as it arrives, and the events are put back
     * together into the same message the API would return unstreamed (content blocks, stop reason,
     * usage), so the tool loop can send Claude's turn back exactly as it came.
     */
    private suspend fun post(
        messages: JSONArray,
        tools: JSONArray,
        apiKey: String,
        withSearch: Boolean,
        onText: (String) -> Unit,
    ): JSONObject = coroutineScope {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", MAX_TOKENS)
            .put("system", "$SYSTEM_PROMPT ${now()}")
            .put("messages", messages)
            .put("stream", true)
        if (tools.length() > 0) body.put("tools", tools)

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            doOutput = true
            connectTimeout = 15_000
            // Between streamed events; a search can leave a gap of several seconds.
            readTimeout = 60_000
        }
        // A blocked read doesn't notice cancellation; dropping the connection ends it at once.
        val hangUp = launch {
            try {
                awaitCancellation()
            } finally {
                conn.disconnect()
            }
        }
        try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode == 401) throw InvalidApiKeyException()
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                val message = errorMessage(err, conn.responseCode)
                if (withSearch && conn.responseCode == 400 && message.contains("web_search")) throw SearchUnavailableException()
                error(message)
            }

            val blocks = sortedMapOf<Int, JSONObject>()
            val inputs = mutableMapOf<Int, StringBuilder>()
            var stopReason = ""
            var usage = JSONObject()
            var ended = false
            val reader = conn.inputStream.bufferedReader()
            while (true) {
                // Stop reading, and drop the connection, as soon as the wearer interrupts.
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val event = JSONObject(line.removePrefix("data:").trim())
                when (event.optString("type")) {
                    "content_block_start" -> {
                        val index = event.getInt("index")
                        val block = event.getJSONObject("content_block")
                        blocks[index] = block
                        // Text after a tool call or search result is a new sentence.
                        if (block.optString("type") != "text") onText(" ")
                        // A tool call's input arrives as pieces of JSON, put together at the block's end.
                        if (block.optString("type").endsWith("tool_use")) inputs[index] = StringBuilder()
                    }
                    "content_block_delta" -> {
                        val index = event.getInt("index")
                        val block = blocks[index] ?: continue
                        val delta = event.getJSONObject("delta")
                        when (delta.optString("type")) {
                            "text_delta" -> {
                                val text = delta.optString("text")
                                block.put("text", block.optString("text") + text)
                                if (text.isNotEmpty()) onText(text)
                            }
                            "input_json_delta" -> inputs[index]?.append(delta.optString("partial_json"))
                            "citations_delta" -> {
                                val citations = block.optJSONArray("citations") ?: JSONArray().also { block.put("citations", it) }
                                delta.optJSONObject("citation")?.let { citations.put(it) }
                            }
                        }
                    }
                    "content_block_stop" -> {
                        val index = event.getInt("index")
                        val input = inputs.remove(index)?.toString()
                        if (input != null) blocks[index]?.put("input", if (input.isBlank()) JSONObject() else JSONObject(input))
                    }
                    "message_delta" -> {
                        event.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotEmpty() && it != "null" }?.let { stopReason = it }
                        event.optJSONObject("usage")?.let { usage = it }
                    }
                    "message_stop" -> ended = true
                    "error" -> error(event.optJSONObject("error")?.optString("message") ?: "The reply stream failed")
                }
            }
            currentCoroutineContext().ensureActive()
            // A stream that stops before its end is a lost connection, not a short answer.
            if (!ended) throw IOException("The reply stream ended early")
            JSONObject()
                .put("content", JSONArray(blocks.values.toList()))
                .put("stop_reason", stopReason)
                .put("usage", usage)
        } finally {
            hangUp.cancel()
            conn.disconnect()
        }
    }

    private fun finish(json: JSONObject, used: Set<String>): Reply {
        val stopReason = json.optString("stop_reason")
        if (stopReason == "refusal") return Reply("Sorry, I can't help with that one.", emptyList(), refused = true)

        // With search, the reply is the text after the last search result; earlier text is Claude
        // narrating the search ("I'll look that up"). A response may also begin with a non-text block.
        val content = json.getJSONArray("content")
        val blocks = (0 until content.length()).map { content.getJSONObject(it) }
        val lastResult = blocks.indexOfLast { it.optString("type") == "web_search_tool_result" }
        val texts = blocks.filter { it.optString("type") == "text" }
        val answer = blocks.drop(lastResult + 1).filter { it.optString("type") == "text" }.ifEmpty { texts }
        val text = answer.joinToString("") { it.optString("text") }.trim()

        // Where the answer came from, shown under the reply: cited sites, and the weather service,
        // whose free licence asks for attribution.
        val cited = answer
            .flatMap { block ->
                val citations = block.optJSONArray("citations") ?: JSONArray()
                (0 until citations.length()).mapNotNull { citations.optJSONObject(it)?.optString("url") }
            }
            .mapNotNull { url -> runCatching { URL(url).host.removePrefix("www.") }.getOrNull() }
        val sources = (cited + listOfNotNull("Open-Meteo".takeIf { "get_weather" in used })).distinct()

        val result = if (stopReason == "max_tokens") SpokenText.upToLastSentence(text) else text
        val searches = json.optJSONObject("usage")?.optJSONObject("server_tool_use")?.optInt("web_search_requests") ?: 0
        // Counts and tool names only, never content.
        Log.d(TAG, "reply chars=${result.length} stop=$stopReason searches=$searches tools=$used")
        return Reply(result, sources, truncated = stopReason == "max_tokens")
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

/**
 * Claude's reply, and the websites it cited, if it searched. [refused] means the text is ours, not
 * what was streamed (a refusal, or too many tool rounds); [truncated] means Claude was cut off at
 * the token limit and [text] ends at the last whole sentence.
 */
data class Reply(
    val text: String,
    val sources: List<String>,
    val refused: Boolean = false,
    val truncated: Boolean = false,
) {

    /** The reply as shown on screen: the text, then where it came from. */
    val display: String
        get() = if (sources.isEmpty()) text else "$text\n\nSources: ${sources.joinToString(", ")}"
}

/** The request was refused because of the web search tool, so it is retried without it. */
private class SearchUnavailableException : Exception("web search unavailable")

/** The API rejected the key (HTTP 401). */
class InvalidApiKeyException : Exception("API key rejected")
