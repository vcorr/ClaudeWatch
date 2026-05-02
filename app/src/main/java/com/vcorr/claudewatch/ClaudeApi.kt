package com.vcorr.claudewatch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ClaudeApi {

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-haiku-4-5-20251001"
    private const val MAX_TOKENS = 512

    suspend fun ask(prompt: String, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", MAX_TOKENS)
                put("messages", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }
                ))
            }.toString()

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

            val responseText = if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                error(err)
            }

            JSONObject(responseText)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
        }
    }
}
