package com.vcorr.claudewatch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

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

/** The in-app recogniser: live partial transcript, and the app controls the follow-up window. */
class InAppSpeechInput(private val context: Context) : SpeechInput {

    private var recognizer: SpeechRecognizer? = null
    private var active: SpeechInput.Listener? = null

    override fun start(listener: SpeechInput.Listener) {
        cancel()
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        active = listener
        var ready = false
        sr.setRecognitionListener(object : RecognitionListener {
            private fun current() = active === listener

            override fun onReadyForSpeech(params: Bundle?) {
                ready = true
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
                active = null
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) listener.onNothingHeard() else listener.onResult(text)
            }

            override fun onError(error: Int) {
                if (!current()) return
                active = null
                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> listener.onNothingHeard()
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        listener.onError("Microphone permission needed", routeUnavailable = false)
                    SpeechRecognizer.ERROR_AUDIO -> listener.onError("Microphone problem", routeUnavailable = false)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        listener.onError("Speech recognition needs a connection", routeUnavailable = false)
                    // Failing before it was ever ready (e.g. "no selected voice recognition service")
                    // means this route doesn't work here.
                    else -> listener.onError("Speech recognition failed ($error)", routeUnavailable = !ready)
                }
            }
        })
        sr.startListening(
            SpeechInput.recognizeIntent().putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        )
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        active = null
        recognizer?.cancel()
    }

    override fun destroy() {
        cancel()
        recognizer?.destroy()
        recognizer = null
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
