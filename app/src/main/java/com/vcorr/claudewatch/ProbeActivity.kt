package com.vcorr.claudewatch

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 1 device probe: reports which speech recognition and TTS routes this watch offers,
 * tries each listening route, and times one API round trip. Results are read off the screen.
 */
class ProbeActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var scroll: ScrollView
    private lateinit var tvLog: TextView
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_probe)
        scroll = findViewById(R.id.probe_scroll)
        tvLog = findViewById(R.id.tv_probe_log)

        findViewById<Button>(R.id.btn_probe_listen).setOnClickListener { testInAppListening() }
        findViewById<Button>(R.id.btn_probe_dialog).setOnClickListener { testSystemDialog() }
        findViewById<Button>(R.id.btn_probe_speak).setOnClickListener { testSpeech() }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runChecks()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) runChecks()
    }

    // ── Static checks ───────────────────────────────────────

    private fun runChecks() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        log("ClaudeWatch $version")
        log("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        log("Mic permission: ${if (micGranted) "granted" else "DENIED"}")

        log("— Speech in —")
        log("SpeechRecognizer available: ${SpeechRecognizer.isRecognitionAvailable(this)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            log("On-device recogniser: ${SpeechRecognizer.isOnDeviceRecognitionAvailable(this)}")
        }
        val services = packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo.packageName }
        log("Recognition services: ${services.ifEmpty { listOf("none") }.joinToString()}")
        val dialog = packageManager
            .resolveActivity(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
            ?.activityInfo?.packageName
        log("System dialog: ${dialog ?: "none"}")

        log("— Network —")
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("Bluetooth via phone")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("LTE")
        }
        log("Active network: ${transports.ifEmpty { listOf("none") }.joinToString()}")

        log("— Speech out —")
        tts = TextToSpeech(this) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                log("TTS: failed to start ($status)")
                return@TextToSpeech
            }
            log("TTS engines: ${engine.engines.joinToString { it.label }}")
            log("Default engine: ${engine.defaultEngine}")
            val english = engine.voices.orEmpty().filter { it.locale.language == "en" }
            log("English voices: ${english.size}, offline: ${english.count { !it.isNetworkConnectionRequired }}")
        }

        log("— Claude round trip —")
        scope.launch {
            val key = ApiKeyStore.read(this@ProbeActivity)
            if (key == null) {
                log("No API key set")
                return@launch
            }
            val start = SystemClock.elapsedRealtime()
            try {
                ClaudeApi.ask("Reply with just the word OK.", key)
                log("API round trip: ${SystemClock.elapsedRealtime() - start} ms")
            } catch (e: Exception) {
                log("API failed after ${SystemClock.elapsedRealtime() - start} ms: ${e.message?.take(80)}")
            }
        }
    }

    // ── Interactive tests ───────────────────────────────────

    private fun testInAppListening() {
        recognizer?.destroy()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("In-app listening: not available")
            return
        }
        val start = SystemClock.elapsedRealtime()
        fun t() = "${(SystemClock.elapsedRealtime() - start) / 100 / 10.0}s"
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = log("Listening… speak now (${t()})")
            override fun onBeginningOfSpeech() = log("Heard speech (${t()})")
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = log("End of speech (${t()})")
            override fun onError(error: Int) = log("In-app listening error: ${errorName(error)} (${t()})")
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) log("… $text")
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                log("In-app result: \"${text ?: ""}\" (${t()})")
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        log("In-app listening: starting")
        sr.startListening(intent)
        scope.launch {
            delay(8_000)
            if (recognizer === sr) sr.stopListening()
        }
    }

    private fun testSystemDialog() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say something")
        try {
            log("System dialog: opening")
            dialogStart = SystemClock.elapsedRealtime()
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_DIALOG)
        } catch (e: ActivityNotFoundException) {
            log("System dialog: not available")
        }
    }

    private var dialogStart = 0L

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_DIALOG) return
        val seconds = (SystemClock.elapsedRealtime() - dialogStart) / 100 / 10.0
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        val outcome = if (resultCode == RESULT_OK) "\"${text ?: ""}\"" else "cancelled ($resultCode)"
        log("System dialog result: $outcome (${seconds}s)")
    }

    private fun testSpeech() {
        val engine = tts ?: run {
            log("TTS not ready")
            return
        }
        engine.speak("This is how Claude will sound on your watch.", TextToSpeech.QUEUE_FLUSH, null, "probe")
        log("Speaking a sample. Was it loud and clear enough?")
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun log(line: String) {
        runOnUiThread {
            tvLog.append(if (tvLog.text.isEmpty()) line else "\n$line")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun errorName(code: Int) = when (code) {
        1 -> "NETWORK_TIMEOUT"
        2 -> "NETWORK"
        3 -> "AUDIO"
        4 -> "SERVER"
        5 -> "CLIENT"
        6 -> "SPEECH_TIMEOUT (heard nothing)"
        7 -> "NO_MATCH (didn't understand)"
        8 -> "RECOGNIZER_BUSY"
        9 -> "INSUFFICIENT_PERMISSIONS"
        10 -> "TOO_MANY_REQUESTS"
        11 -> "SERVER_DISCONNECTED"
        12 -> "LANGUAGE_NOT_SUPPORTED"
        13 -> "LANGUAGE_UNAVAILABLE"
        else -> "code $code"
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        tts?.shutdown()
        scope.cancel()
    }

    private companion object {
        const val REQ_MIC = 1
        const val REQ_DIALOG = 2
    }
}
