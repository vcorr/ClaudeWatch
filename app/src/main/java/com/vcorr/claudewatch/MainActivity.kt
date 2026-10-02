package com.vcorr.claudewatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.SpeechRecognizer
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
import com.vcorr.claudewatch.core.Conversation
import com.vcorr.claudewatch.core.SpokenText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet4Address

class MainActivity : Activity() {

    private enum class State { IDLE, LISTENING, FOLLOW_UP, THINKING, SPEAKING, TYPING, SETUP }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var layoutVoice: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var scrollTranscript: ScrollView
    private lateinit var tvTranscript: TextView
    private lateinit var btnTalk: Button
    private lateinit var layoutTyping: LinearLayout
    private lateinit var etPrompt: EditText
    private lateinit var layoutSetup: ScrollView
    private lateinit var tvSetup: TextView

    private var state = State.IDLE

    // Bumped by every new turn or interruption; callbacks from older turns are ignored.
    private var turnToken = 0

    private val conversation = Conversation()
    private lateinit var speaker: Speaker
    private var inAppInput: InAppSpeechInput? = null
    private lateinit var dialogInput: DialogSpeechInput
    private var useDialog = false
    private var requestJob: Job? = null
    private var followUpJob: Job? = null
    private var foreground = false
    private var listenOnLaunch = false
    private var listenAfterPermission = false

    private var setupServer: KeySetupServer? = null
    private var setupPort = 0
    private var setupAddress: String? = null
    private var setupNotice: String? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        layoutVoice = findViewById(R.id.layout_voice)
        tvStatus = findViewById(R.id.tv_status)
        scrollTranscript = findViewById(R.id.scroll_transcript)
        tvTranscript = findViewById(R.id.tv_transcript)
        btnTalk = findViewById(R.id.btn_talk)
        layoutTyping = findViewById(R.id.layout_typing)
        etPrompt = findViewById(R.id.et_prompt)
        layoutSetup = findViewById(R.id.layout_setup)
        tvSetup = findViewById(R.id.tv_setup)

        speaker = Speaker(this)
        dialogInput = DialogSpeechInput(this, REQ_DIALOG)
        useDialog = getPreferences(MODE_PRIVATE).getBoolean(PREF_USE_DIALOG, false)

        btnTalk.setOnClickListener { onTalkTapped() }
        findViewById<Button>(R.id.btn_type).setOnClickListener { showTyping() }
        findViewById<Button>(R.id.btn_new).setOnClickListener { newChat() }
        findViewById<Button>(R.id.btn_probe).setOnClickListener {
            interrupt(null)
            startActivity(Intent(this, ProbeActivity::class.java))
        }
        findViewById<Button>(R.id.btn_send).setOnClickListener { submitTyped() }
        etPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submitTyped(); true } else false
        }

        // Launching the app goes straight into listening, like a voice assistant.
        listenOnLaunch = savedInstanceState == null
        render()
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onResume() {
        super.onResume()
        if (dialogInput.isOpen) return
        scope.launch {
            // Also restarts setup after a pause, e.g. when it was opened because the key was rejected.
            if (state == State.SETUP || ApiKeyStore.read(this@MainActivity) == null) {
                showSetup(setupNotice)
            } else if (listenOnLaunch) {
                listenOnLaunch = false
                startListening(followUp = false)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopSetup()
    }

    override fun onStop() {
        super.onStop()
        foreground = false
        // The system speech dialog stops this activity while it is open; that is expected.
        if (dialogInput.isOpen) return
        // The microphone may only be used while the app is visible; a reply may finish speaking.
        if (state == State.LISTENING || state == State.FOLLOW_UP) interrupt(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSetup()
        inAppInput?.destroy()
        dialogInput.destroy()
        speaker.shutdown()
        scope.cancel()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        dialogInput.handleResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC || !listenAfterPermission) return
        listenAfterPermission = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening(followUp = false)
        } else {
            goIdle("Microphone permission is needed to talk")
        }
    }

    // ── The voice loop ──────────────────────────────────────

    private fun onTalkTapped() {
        when (state) {
            State.IDLE -> startListening(followUp = false)
            State.LISTENING -> currentInput().stop()
            State.FOLLOW_UP, State.THINKING, State.SPEAKING -> interrupt(null)
            State.TYPING, State.SETUP -> Unit
        }
    }

    private fun currentInput(): SpeechInput =
        if (!useDialog && SpeechRecognizer.isRecognitionAvailable(this)) {
            inAppInput ?: InAppSpeechInput(this).also { inAppInput = it }
        } else {
            dialogInput
        }

    private fun startListening(followUp: Boolean) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listenAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        val token = ++turnToken
        val input = currentInput()
        state = if (followUp) State.FOLLOW_UP else State.LISTENING
        keepScreenOn(true)
        render()
        tick()

        var heardSpeech = false
        input.start(object : SpeechInput.Listener {
            private fun current() = token == turnToken

            override fun onListening() {
                if (current()) render()
            }

            override fun onSpeechStarted() {
                if (!current()) return
                heardSpeech = true
                followUpJob?.cancel()
                state = State.LISTENING
                render()
            }

            override fun onPartial(text: String) {
                if (!current()) return
                heardSpeech = true
                followUpJob?.cancel()
                state = State.LISTENING
                render()
                tvTranscript.text = "$text…"
            }

            override fun onResult(text: String) {
                if (!current()) return
                followUpJob?.cancel()
                tick()
                ask(text)
            }

            override fun onNothingHeard() {
                if (!current()) return
                followUpJob?.cancel()
                goIdle(if (followUp) null else "Didn't catch that. Tap to try again.")
            }

            override fun onError(message: String, routeUnavailable: Boolean) {
                if (!current()) return
                followUpJob?.cancel()
                if (routeUnavailable && input !== dialogInput) {
                    // The in-app recogniser doesn't work here: switch to the system dialog for good.
                    useDialog = true
                    getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_USE_DIALOG, true).apply()
                    startListening(followUp)
                    return
                }
                goIdle(message)
            }
        })

        // In-app follow-ups close after 3.5 s of silence; the system dialog has its own timeout.
        if (followUp && input !== dialogInput) {
            followUpJob = scope.launch {
                for (secondsLeft in 3 downTo 1) {
                    if (state == State.FOLLOW_UP) tvStatus.text = "Listening for a follow-up… $secondsLeft"
                    delay(FOLLOW_UP_WINDOW_MS / 3)
                }
                if (token == turnToken && !heardSpeech) {
                    input.cancel()
                    goIdle(null)
                }
            }
        }
    }

    private fun ask(text: String) {
        val token = turnToken
        conversation.addUser(text)
        state = State.THINKING
        tvTranscript.text = "You: $text"
        render()

        requestJob = scope.launch {
            val key = ApiKeyStore.read(this@MainActivity)
            if (key == null) {
                showSetup(null)
                return@launch
            }
            try {
                val reply = ClaudeApi.reply(conversation.forRequest(), key)
                if (token != turnToken) return@launch
                conversation.addAssistant(reply)
                tvTranscript.text = "You: $text\n\n$reply"
                scrollTranscript.scrollTo(0, 0)
                speak(reply, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidApiKeyException) {
                conversation.dropUnanswered()
                showSetup("The API key was rejected.")
            } catch (e: Exception) {
                if (token != turnToken) return@launch
                conversation.dropUnanswered()
                goIdle("Couldn't reach Claude: ${e.message?.take(80) ?: "unknown error"}")
            }
        }
    }

    private fun speak(reply: String, token: Int) {
        state = State.SPEAKING
        render()
        speaker.speak(SpokenText.clean(reply)) {
            if (token != turnToken) return@speak
            if (!foreground) {
                goIdle(null)
                return@speak
            }
            // A short pause, so the microphone doesn't catch the tail of the reply.
            scope.launch {
                delay(MIC_DELAY_MS)
                if (token == turnToken && foreground) startListening(followUp = true)
            }
        }
    }

    /** Stops whatever is happening: listening, waiting for Claude, or speaking. */
    private fun interrupt(message: String?) {
        turnToken++
        requestJob?.cancel()
        followUpJob?.cancel()
        inAppInput?.cancel()
        dialogInput.cancel()
        speaker.stop()
        conversation.dropUnanswered()
        goIdle(message)
    }

    private fun goIdle(message: String?) {
        state = State.IDLE
        keepScreenOn(false)
        render()
        if (message != null) tvStatus.text = message
    }

    private fun newChat() {
        interrupt(null)
        conversation.clear()
        tvTranscript.text = ""
        tvStatus.text = "New chat. Tap to talk."
    }

    // ── Typing ──────────────────────────────────────────────

    private fun showTyping() {
        interrupt(null)
        state = State.TYPING
        render()
        etPrompt.requestFocus()
    }

    private fun submitTyped() {
        val text = etPrompt.text.toString().trim()
        if (text.isEmpty()) return
        etPrompt.text.clear()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(etPrompt.windowToken, 0)
        turnToken++
        keepScreenOn(true)
        ask(text)
    }

    // ── Screen ──────────────────────────────────────────────

    private fun render() {
        layoutVoice.visibility = if (state == State.TYPING || state == State.SETUP) View.GONE else View.VISIBLE
        layoutTyping.visibility = if (state == State.TYPING) View.VISIBLE else View.GONE
        layoutSetup.visibility = if (state == State.SETUP) View.VISIBLE else View.GONE
        tvStatus.text = when (state) {
            State.IDLE -> "Tap to talk"
            State.LISTENING -> "Listening…"
            State.FOLLOW_UP -> "Listening for a follow-up…"
            State.THINKING -> "Thinking…"
            State.SPEAKING -> "Speaking…"
            State.TYPING, State.SETUP -> ""
        }
        btnTalk.text = when (state) {
            State.LISTENING -> "Done"
            State.FOLLOW_UP, State.THINKING, State.SPEAKING -> "Stop"
            else -> "Talk"
        }
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun tick() {
        getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    // ── Key setup over the local Wi-Fi ──────────────────────

    private fun showSetup(notice: String?) {
        interrupt(null)
        state = State.SETUP
        render()
        keepScreenOn(true)
        if (setupServer != null) return

        val server = KeySetupServer(
            this,
            onKeySaved = {
                runOnUiThread {
                    setupNotice = null
                    stopSetup()
                    goIdle("Key saved. Tap to talk.")
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
    }

    private companion object {
        const val REQ_MIC = 1
        const val REQ_DIALOG = 2
        const val PREF_USE_DIALOG = "use_dialog"
        const val FOLLOW_UP_WINDOW_MS = 3_500L
        const val MIC_DELAY_MS = 200L
    }
}
