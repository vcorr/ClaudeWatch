package com.vcorr.claudewatch

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.vcorr.claudewatch.core.SpokenText
import java.util.Locale

/**
 * Speaks replies sentence by sentence with the system TTS, holding transient audio focus while it
 * talks. [speak]'s callback runs on the main thread once the last sentence has finished, or straight
 * away if TTS is unavailable.
 */
class Speaker(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private lateinit var tts: TextToSpeech
    private var ready = false
    private var failed = false
    private var queued: Pair<String, () -> Unit>? = null
    private var onFinished: (() -> Unit)? = null
    private var lastUtteranceId: String? = null
    private var counter = 0

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    configure()
                    ready = true
                    queued?.let { (text, done) -> speak(text, done) }
                } else {
                    failed = true
                    queued?.second?.invoke()
                }
                queued = null
            }
        }
    }

    private fun configure() {
        tts.setAudioAttributes(attributes)
        tts.setLanguage(Locale.US)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) = finishIfLast(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finishIfLast(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = finishIfLast(utteranceId)
        })
    }

    fun speak(text: String, onDone: () -> Unit) {
        stop()
        if (failed) {
            main.post(onDone)
            return
        }
        if (!ready) {
            queued = text to onDone
            return
        }
        val sentences = SpokenText.sentences(text).filter { it.isNotBlank() }
        if (sentences.isEmpty()) {
            main.post(onDone)
            return
        }
        onFinished = onDone
        audioManager.requestAudioFocus(focusRequest)
        val base = "u${++counter}"
        sentences.forEachIndexed { i, sentence ->
            tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, "$base-$i")
        }
        lastUtteranceId = "$base-${sentences.lastIndex}"
    }

    fun stop() {
        queued = null
        onFinished = null
        lastUtteranceId = null
        if (ready) tts.stop()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun finishIfLast(utteranceId: String?) {
        main.post {
            if (utteranceId == null || utteranceId != lastUtteranceId) return@post
            lastUtteranceId = null
            audioManager.abandonAudioFocusRequest(focusRequest)
            val done = onFinished
            onFinished = null
            done?.invoke()
        }
    }
}
