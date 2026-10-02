package com.vcorr.claudewatch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/** One way of turning the wearer's speech into text. Callbacks arrive on the main thread. */
interface SpeechInput {

    interface Listener {
        fun onListening()
        fun onSpeechStarted()
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onNothingHeard()

        /** [routeUnavailable] means this route can't work on this watch, so another should be tried. */
        fun onError(message: String, routeUnavailable: Boolean)
    }

    fun start(listener: Listener)

    /** Stops capturing but still delivers what was heard. */
    fun stop()

    /** Stops and delivers nothing. */
    fun cancel()

    fun destroy()

    companion object {
        fun recognizeIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
    }
}

/**
 * The in-app recogniser: live partial transcript, and the app controls the follow-up window.
 *
 * A watch can have several speech services (a Galaxy Watch has Google's and Samsung's), and the
 * default one may refuse other apps. So this tries the default, then each installed service,
 * Google's first, and sticks with the first that gets as far as listening.
 */
class InAppSpeechInput(private val context: Context) : SpeechInput {

    private val main = Handler(Looper.getMainLooper())
    private val candidates: List<ComponentName?> by lazy { findCandidates() }
    private var candidateIndex = 0
    private var recognizer: SpeechRecognizer? = null
    private var active: SpeechInput.Listener? = null
    private var watchdog: Runnable? = null

    // Each attempt on a service gets a number, so late callbacks from an abandoned one are ignored.
    private var attempt = 0

    override fun start(listener: SpeechInput.Listener) {
        cancel()
        active = listener
        startWith(listener)
    }

    private fun startWith(listener: SpeechInput.Listener) {
        val component = candidates[candidateIndex]
        val sr = recognizer ?: (
            if (component == null) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context, component)
            }
        ).also { recognizer = it }
        var ready = false
        val thisAttempt = ++attempt

        // A service that neither starts nor fails counts as not working.
        val timeout = Runnable {
            if (active === listener && attempt == thisAttempt && !ready) {
                tryNextService(listener, "speech service didn't start")
            }
        }
        watchdog = timeout
        main.postDelayed(timeout, START_TIMEOUT_MS)

        sr.setRecognitionListener(object : RecognitionListener {
            private fun current() = active === listener && attempt == thisAttempt

            override fun onReadyForSpeech(params: Bundle?) {
                ready = true
                main.removeCallbacks(timeout)
                if (current()) listener.onListening()
            }

            override fun onBeginningOfSpeech() {
                if (current()) listener.onSpeechStarted()
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (current() && !text.isNullOrBlank()) listener.onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                if (!current()) return
                main.removeCallbacks(timeout)
                active = null
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) listener.onNothingHeard() else listener.onResult(text)
            }

            override fun onError(error: Int) {
                if (!current()) return
                main.removeCallbacks(timeout)
                if (!ready && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && error != SpeechRecognizer.ERROR_NO_MATCH) {
                    // Failed before it ever listened, e.g. "no selected voice recognition service".
                    tryNextService(listener, "error $error")
                    return
                }
                active = null
                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> listener.onNothingHeard()
                    SpeechRecognizer.ERROR_AUDIO -> listener.onError("Microphone problem", routeUnavailable = false)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        listener.onError("Speech recognition needs a connection", routeUnavailable = false)
                    else -> listener.onError("Speech recognition failed ($error)", routeUnavailable = false)
                }
            }
        })
        sr.startListening(
            SpeechInput.recognizeIntent()
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        )
    }

    private fun tryNextService(listener: SpeechInput.Listener, reason: String) {
        Log.d(TAG, "speech service ${candidates[candidateIndex] ?: "default"} unusable: $reason")
        recognizer?.destroy()
        recognizer = null
        candidateIndex++
        if (candidateIndex < candidates.size) {
            startWith(listener)
        } else {
            candidateIndex = 0
            active = null
            listener.onError("No speech service works in-app", routeUnavailable = true)
        }
    }

    private fun findCandidates(): List<ComponentName?> {
        val services = context.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .sortedBy { if (it.packageName.startsWith("com.google")) 0 else 1 }
        return listOf<ComponentName?>(null) + services
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        active = null
        watchdog?.let(main::removeCallbacks)
        recognizer?.cancel()
    }

    override fun destroy() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private companion object {
        const val TAG = "ClaudeWatch"
        const val START_TIMEOUT_MS = 4_000L
    }
}

/**
 * The system speech dialog, the route Google documents for Wear OS. The activity forwards
 * [onActivityResult] results through [handleResult].
 */
class DialogSpeechInput(private val activity: Activity, private val requestCode: Int) : SpeechInput {

    private var active: SpeechInput.Listener? = null

    val isOpen: Boolean get() = active != null

    override fun start(listener: SpeechInput.Listener) {
        active = listener
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(SpeechInput.recognizeIntent(), requestCode)
            listener.onListening()
        } catch (e: ActivityNotFoundException) {
            active = null
            listener.onError("No speech recogniser on this watch", routeUnavailable = true)
        }
    }

    /** Returns true if the result was this dialog's. */
    fun handleResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != this.requestCode) return false
        val listener = active ?: return true
        active = null
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) listener.onResult(text) else listener.onNothingHeard()
        return true
    }

    override fun stop() {}

    override fun cancel() {
        if (active != null) activity.finishActivity(requestCode)
        active = null
    }

    override fun destroy() = cancel()
}
