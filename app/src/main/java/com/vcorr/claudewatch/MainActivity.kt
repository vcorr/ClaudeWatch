package com.vcorr.claudewatch

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.wear.input.RemoteInputIntentHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var btnAsk: Button
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutResult: ScrollView
    private lateinit var tvResponse: TextView
    private lateinit var btnAgain: Button

    private val inputKey = "prompt"

    private val inputLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val bundle = RemoteInput.getResultsFromIntent(result.data ?: return@registerForActivityResult)
        val prompt = bundle?.getCharSequence(inputKey)?.toString() ?: return@registerForActivityResult
        askClaude(prompt)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnAsk = findViewById(R.id.btn_ask)
        layoutLoading = findViewById(R.id.layout_loading)
        layoutResult = findViewById(R.id.layout_result)
        tvResponse = findViewById(R.id.tv_response)
        btnAgain = findViewById(R.id.btn_again)

        btnAsk.setOnClickListener { launchInput() }
        btnAgain.setOnClickListener { showIdle() }
    }

    private fun launchInput() {
        val remoteInput = RemoteInput.Builder(inputKey)
            .setLabel("Ask Claude…")
            .build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        inputLauncher.launch(intent)
    }

    private fun askClaude(prompt: String) {
        showLoading()
        scope.launch {
            val apiKey = BuildConfig.CLAUDE_API_KEY
            if (apiKey.isBlank()) {
                showError("API key not set")
                return@launch
            }
            ClaudeApi.ask(prompt, apiKey).fold(
                onSuccess = { showResult(it) },
                onFailure = { showError(it.message ?: "Error") }
            )
        }
    }

    private fun showIdle() {
        btnAsk.visibility = View.VISIBLE
        layoutLoading.visibility = View.GONE
        layoutResult.visibility = View.GONE
    }

    private fun showLoading() {
        btnAsk.visibility = View.GONE
        layoutLoading.visibility = View.VISIBLE
        layoutResult.visibility = View.GONE
    }

    private fun showResult(text: String) {
        tvResponse.text = text
        btnAsk.visibility = View.GONE
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
