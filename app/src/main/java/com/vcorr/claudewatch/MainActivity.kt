package com.vcorr.claudewatch

import android.app.Activity
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.View
import android.view.WindowManager
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
import java.io.IOException
import java.net.Inet4Address

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var layoutIdle: LinearLayout
    private lateinit var etPrompt: EditText
    private lateinit var btnSend: Button
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutResult: ScrollView
    private lateinit var tvResponse: TextView
    private lateinit var btnAgain: Button
    private lateinit var layoutSetup: ScrollView
    private lateinit var tvSetup: TextView

    private var setupServer: KeySetupServer? = null
    private var setupPort = 0
    private var setupAddress: String? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

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
        layoutSetup = findViewById(R.id.layout_setup)
        tvSetup = findViewById(R.id.tv_setup)

        btnSend.setOnClickListener { submit() }
        btnAgain.setOnClickListener { showIdle() }
        findViewById<Button>(R.id.btn_probe).setOnClickListener {
            startActivity(android.content.Intent(this, ProbeActivity::class.java))
        }

        etPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
    }

    override fun onResume() {
        super.onResume()
        scope.launch {
            // Also restarts setup after a pause, e.g. when it was opened because the key was rejected.
            if (layoutSetup.visibility == View.VISIBLE || ApiKeyStore.read(this@MainActivity) == null) {
                showSetup(setupNotice)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopSetup()
    }

    // ── Key setup over the local Wi-Fi ──────────────────────

    private fun showSetup(notice: String?) {
        layoutIdle.visibility = View.GONE
        layoutLoading.visibility = View.GONE
        layoutResult.visibility = View.GONE
        layoutSetup.visibility = View.VISIBLE
        if (setupServer != null) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val server = KeySetupServer(
            this,
            onKeySaved = {
                runOnUiThread {
                    setupNotice = null
                    stopSetup()
                    showIdle()
                    Toast.makeText(this, "Key saved", Toast.LENGTH_SHORT).show()
                }
            },
            onLockedOut = {
                runOnUiThread {
                    stopSetup()
                    tvSetup.text = "Too many wrong PINs.\n\nClose and reopen ClaudeWatch for a new PIN."
                }
            },
        )
        setupPort = try {
            server.start()
        } catch (e: IOException) {
            tvSetup.text = "Couldn't start key setup: ${e.message}"
            return
        }
        setupServer = server
        setupNotice = notice
        requestWifi()
        renderSetup()
    }

    private var setupNotice: String? = null

    private fun renderSetup() {
        val server = setupServer ?: return
        val notice = setupNotice?.let { "$it\n\n" }.orEmpty()
        val ip = setupAddress
        tvSetup.text = if (ip == null) {
            "${notice}Set your API key\n\nWaiting for Wi-Fi…\nTurn on the watch's Wi-Fi, on the same network as your phone."
        } else {
            "${notice}Set your API key\n\nOn your phone, open\nhttp://$ip:$setupPort\n\nPIN  ${server.pin}\n\nPhone and watch on the same Wi-Fi."
        }
    }

    /** Wear OS may keep Wi-Fi off while Bluetooth is connected; asking for it brings it up. */
    private fun requestWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val ip = linkProperties.linkAddresses
                    .map { it.address }
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress
                runOnUiThread {
                    setupAddress = ip
                    renderSetup()
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    setupAddress = null
                    renderSetup()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm.requestNetwork(request, callback)
        wifiCallback = callback
    }

    private fun stopSetup() {
        setupServer?.stop()
        setupServer = null
        setupAddress = null
        wifiCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        wifiCallback = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ── Asking Claude ───────────────────────────────────────

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
                showSetup(null)
                return@launch
            }
            try {
                val response = ClaudeApi.ask(prompt, apiKey)
                showResult(response)
            } catch (e: InvalidApiKeyException) {
                showSetup("The API key was rejected.")
            } catch (e: Exception) {
                showError(e.message ?: "Error")
            }
        }
    }

    private fun showIdle() {
        layoutSetup.visibility = View.GONE
        layoutIdle.visibility = View.VISIBLE
        layoutLoading.visibility = View.GONE
        layoutResult.visibility = View.GONE
        etPrompt.text.clear()
    }

    private fun showLoading() {
        layoutSetup.visibility = View.GONE
        layoutIdle.visibility = View.GONE
        layoutLoading.visibility = View.VISIBLE
        layoutResult.visibility = View.GONE
    }

    private fun showResult(text: String) {
        tvResponse.text = text
        layoutSetup.visibility = View.GONE
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
