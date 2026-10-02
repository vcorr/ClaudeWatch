package com.vcorr.claudewatch

import android.content.Context
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * A one-page web form on the local Wi-Fi network, so the API key can be pasted from a phone's
 * browser with no companion app or computer. It runs only while the setup screen is showing,
 * the six-digit PIN shown on the watch must accompany the key, and five wrong PINs shut it down.
 * Callbacks run on the server thread.
 */
class KeySetupServer(
    private val context: Context,
    private val onKeySaved: () -> Unit,
    private val onLockedOut: () -> Unit,
) {

    val pin: String = (SecureRandom().nextInt(900_000) + 100_000).toString()

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var failedAttempts = 0

    /** Starts listening and returns the port. Throws IOException if no socket can be opened. */
    fun start(): Int {
        val socket = try {
            ServerSocket(PREFERRED_PORT)
        } catch (e: IOException) {
            ServerSocket(0)
        }
        serverSocket = socket
        Thread({ acceptLoop(socket) }, "key-setup-server").start()
        return socket.localPort
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: SocketException) {
                return
            } catch (e: IOException) {
                continue
            }
            client.use { handle(it) }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val sep = line.indexOf(':')
                if (sep > 0 && line.substring(0, sep).trim().equals("Content-Length", ignoreCase = true)) {
                    contentLength = line.substring(sep + 1).trim().toIntOrNull() ?: 0
                }
            }
            val out = client.getOutputStream()
            when {
                requestLine.startsWith("GET / ") -> respond(out, 200, formPage(null))
                requestLine.startsWith("POST / ") -> {
                    if (contentLength !in 1..MAX_BODY_CHARS) {
                        respond(out, 400, formPage("Something went wrong. Try again."))
                        return
                    }
                    // Form-encoded bodies are ASCII, so characters and bytes match.
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    handleSubmit(out, String(buf, 0, read))
                }
                else -> respond(out, 404, "Not found")
            }
        } catch (_: IOException) {
        }
    }

    private fun handleSubmit(out: OutputStream, body: String) {
        val fields = body.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) {
                null
            } else {
                URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                    URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }
        }.toMap()

        if (fields["pin"]?.trim() != pin) {
            failedAttempts++
            if (failedAttempts >= MAX_ATTEMPTS) {
                respond(out, 403, resultPage("Too many wrong PINs. Reopen ClaudeWatch on the watch for a new PIN."))
                stop()
                onLockedOut()
            } else {
                respond(out, 403, formPage("Wrong PIN. Check the watch screen."))
            }
            return
        }

        val key = fields["key"]?.trim().orEmpty()
        if (!key.startsWith("sk-ant-") || key.length > MAX_KEY_CHARS) {
            respond(out, 400, formPage("That doesn't look like an Anthropic API key. They start with sk-ant-."))
            return
        }
        if (!ApiKeyStore.write(context, key)) {
            respond(out, 500, formPage("The watch couldn't save the key. Try again."))
            return
        }
        respond(out, 200, resultPage("Saved. You can close this page and use the watch."))
        stop()
        onKeySaved()
    }

    private fun respond(out: OutputStream, status: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun page(content: String) = """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>ClaudeWatch key</title>
        <style>
          body { font-family: system-ui, sans-serif; margin: 24px; max-width: 28rem; background: #111; color: #eee; }
          label { display: block; margin-top: 16px; }
          input { width: 100%; box-sizing: border-box; font-size: 1.1rem; padding: 10px; margin-top: 6px; }
          button { margin-top: 20px; font-size: 1.1rem; padding: 10px 20px; }
          .error { color: #ff8a80; }
        </style></head>
        <body>$content</body></html>
    """.trimIndent()

    private fun formPage(error: String?) = page(
        """
        <h1>ClaudeWatch</h1>
        <p>Paste your Anthropic API key and the PIN shown on the watch.</p>
        ${if (error != null) "<p class=\"error\">$error</p>" else ""}
        <form method="post" action="/">
          <label>PIN<input name="pin" inputmode="numeric" autocomplete="off" required></label>
          <label>API key<input name="key" type="password" autocomplete="off" autocapitalize="off" spellcheck="false" required></label>
          <button type="submit">Save to watch</button>
        </form>
        """.trimIndent()
    )

    private fun resultPage(message: String) = page("<h1>ClaudeWatch</h1><p>$message</p>")

    private companion object {
        const val PREFERRED_PORT = 8080
        const val MAX_ATTEMPTS = 5
        const val MAX_KEY_CHARS = 500
        const val MAX_BODY_CHARS = 4_096
    }
}
