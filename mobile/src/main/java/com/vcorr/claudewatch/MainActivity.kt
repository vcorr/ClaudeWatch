package com.vcorr.claudewatch

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var layoutIdle: LinearLayout
    private lateinit var etPrompt: EditText
    private lateinit var btnSend: Button
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutResult: ScrollView
    private lateinit var tvResponse: TextView
    private lateinit var btnAgain: Button
    private lateinit var etKey: EditText
    private lateinit var btnSendKey: Button
    private lateinit var tvKeyStatus: TextView

    private var keyReplyTimeout: Job? = null

    private val keyReplyListener = MessageClient.OnMessageReceivedListener { event ->
        when (event.path) {
            KeySync.PATH_SAVED -> keyStatus("The watch saved the key.")
            KeySync.PATH_FAILED -> keyStatus("The watch couldn't save the key. Try again.")
            else -> return@OnMessageReceivedListener
        }
        keyReplyTimeout?.cancel()
        btnSendKey.isEnabled = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyWindowInsets(findViewById(R.id.root))

        layoutIdle = findViewById(R.id.layout_idle)
        etPrompt = findViewById(R.id.et_prompt)
        btnSend = findViewById(R.id.btn_send)
        layoutLoading = findViewById(R.id.layout_loading)
        layoutResult = findViewById(R.id.layout_result)
        tvResponse = findViewById(R.id.tv_response)
        btnAgain = findViewById(R.id.btn_again)
        etKey = findViewById(R.id.et_key)
        btnSendKey = findViewById(R.id.btn_send_key)
        tvKeyStatus = findViewById(R.id.tv_key_status)

        btnSendKey.setOnClickListener { sendKeyToWatch() }

        btnSend.setOnClickListener { submit() }
        btnAgain.setOnClickListener { showIdle() }

        etPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
    }

    // Targeting API 35+ makes the window edge-to-edge, so pad for the system bars and keyboard ourselves.
    private fun applyWindowInsets(root: View) {
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(left + insets.left, top + insets.top, right + insets.right, bottom + insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(keyReplyListener)
    }

    override fun onPause() {
        super.onPause()
        Wearable.getMessageClient(this).removeListener(keyReplyListener)
    }

    private fun sendKeyToWatch() {
        val key = etKey.text.toString().trim()
        if (key.isEmpty()) return
        btnSendKey.isEnabled = false
        keyReplyTimeout?.cancel()
        scope.launch {
            // Keep a copy here too, so the phone's own test chat works.
            withContext(Dispatchers.IO) { ApiKeyStore.write(this@MainActivity, key) }
            etKey.text.clear()
            keyStatus("Looking for the watch…")
            // Armed before sending: the watch's reply can arrive before sendMessage() returns,
            // and the reply listener cancels this job.
            val timeout = scope.launch {
                delay(15_000)
                keyStatus(
                    "No reply from the watch. Is ClaudeWatch installed there, " +
                        "from the same build as this app?"
                )
                btnSendKey.isEnabled = true
            }
            keyReplyTimeout = timeout
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                if (nodes.isEmpty()) {
                    timeout.cancel()
                    keyStatus("Saved on this phone, but no watch is connected.")
                    btnSendKey.isEnabled = true
                    return@launch
                }
                val bytes = key.toByteArray(Charsets.UTF_8)
                nodes.forEach { node ->
                    Wearable.getMessageClient(this@MainActivity)
                        .sendMessage(node.id, KeySync.PATH_KEY, bytes)
                        .await()
                }
                if (timeout.isActive) keyStatus("Sent. Waiting for the watch…")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                timeout.cancel()
                keyStatus("Couldn't reach the watch: ${e.message ?: "unknown error"}")
                btnSendKey.isEnabled = true
            }
        }
    }

    private fun keyStatus(message: String) {
        tvKeyStatus.text = message
    }

    private fun submit() {
        val prompt = etPrompt.text.toString().trim()
        if (prompt.isEmpty()) return
        hideKeyboard()
        askClaude(prompt)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etPrompt.windowToken, 0)
    }

    private fun askClaude(prompt: String) {
        showLoading()
        scope.launch {
            val apiKey = ApiKeyStore.read(this@MainActivity)
            if (apiKey == null) {
                showError("No API key. Paste it above first.")
                return@launch
            }
            try {
                val response = ClaudeApi.ask(prompt, apiKey)
                showResult(response)
            } catch (e: Exception) {
                showError(e.message ?: "Error")
            }
        }
    }

    private fun showIdle() {
        layoutIdle.visibility = View.VISIBLE
        layoutLoading.visibility = View.GONE
        layoutResult.visibility = View.GONE
        etPrompt.text.clear()
    }

    private fun showLoading() {
        layoutIdle.visibility = View.GONE
        layoutLoading.visibility = View.VISIBLE
        layoutResult.visibility = View.GONE
    }

    private fun showResult(text: String) {
        tvResponse.text = text
        layoutIdle.visibility = View.GONE
        layoutLoading.visibility = View.GONE
        layoutResult.visibility = View.VISIBLE
        layoutResult.scrollTo(0, 0)
    }

    private fun showError(message: String) {
        showIdle()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
