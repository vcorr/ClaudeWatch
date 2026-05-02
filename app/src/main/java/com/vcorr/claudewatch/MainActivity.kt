package com.vcorr.claudewatch

import android.app.Activity
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var layoutIdle: LinearLayout
    private lateinit var etPrompt: EditText
    private lateinit var btnSend: Button
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutResult: ScrollView
    private lateinit var tvResponse: TextView
    private lateinit var btnAgain: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        layoutIdle = findViewById(R.id.layout_idle)
        etPrompt = findViewById(R.id.et_prompt)
        btnSend = findViewById(R.id.btn_send)
        layoutLoading = findViewById(R.id.layout_loading)
        layoutResult = findViewById(R.id.layout_result)
        tvResponse = findViewById(R.id.tv_response)
        btnAgain = findViewById(R.id.btn_again)

        btnSend.setOnClickListener { submit() }
        btnAgain.setOnClickListener { showIdle() }

        etPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
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
            val apiKey = BuildConfig.CLAUDE_API_KEY
            if (apiKey.isBlank()) {
                showError("API key not set")
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
