package com.vcorr.claudewatch

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.vcorr.claudewatch.core.SpokenText
import java.util.Locale

/**
 * Speaks replies sentence by sentence with the system TTS, holding transient audio focus while it
 * talks. A reply can be spoken whole with [speak], or as it arrives with [begin], [add] for each
 * sentence, and [end]. The callback runs on the main thread once everything has been spoken (and,
 * when streaming, [end] has been called), or straight away if TTS is unavailable. Call from the
 * main thread.
 *
 * It prefers Google's engine, which is installed on Galaxy Watches beside Samsung's default, and
 * picks the best installed voice in the watch's English. While it talks it lifts a near-silent
 * media volume to an audible floor and puts it back afterwards; a muted watch stays muted.
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private val locale = Locale.forLanguageTag(SpeechInput.englishTag())

    private var tts: TextToSpeech? = null
    private var engine: String? = null
    private var ready = false
    private var failed = false
    private var onFinished: (() -> Unit)? = null
    // A reply is still arriving: more sentences may follow even when the queue runs dry.
    private var streamOpen = false
    // Sentences given before the engine was ready.
    private val waiting = mutableListOf<String>()
    private var lastUtteranceId: String? = null
    private var audioHeld = false
    private var counter = 0
    private var restoreVolume: Int? = null
    private var raisedTo: Int? = null

    /** The engine and voice in use, for the diagnostics screen. */
    var description = "starting"
        private set

    init {
        val google = GOOGLE_TTS.takeIf { installed(it) }
        start(google)
    }

    private fun start(enginePackage: String?) {
        engine = enginePackage
        var created: TextToSpeech? = null
        created = TextToSpeech(appContext, { status ->
            main.post {
                if (tts !== created) return@post
                if (status == TextToSpeech.SUCCESS && !configure() && enginePackage != null) {
                    // Google's engine started but has no English voice installed: use the default.
                    created?.shutdown()
                    start(null)
                } else if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    val pending = waiting.toList()
                    waiting.clear()
                    pending.forEach(::add)
                    finishIfIdle()
                } else if (enginePackage != null) {
                    // Google's engine wouldn't start: fall back to the watch's default.
                    created?.shutdown()
                    start(null)
                } else {
                    failed = true
                    description = "unavailable"
                    waiting.clear()
                    finishIfIdle()
                }
            }
        }, enginePackage)
        tts = created
    }

    /** Sets the engine up; false if it has no usable English voice. */
    private fun configure(): Boolean {
        val tts = tts ?: return false
        tts.setAudioAttributes(attributes)
        val voice = bestVoice(tts)
        val speaks = if (voice != null) {
            tts.voice = voice
            true
        } else {
            tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE ||
                tts.setLanguage(Locale.ENGLISH) >= TextToSpeech.LANG_AVAILABLE
        }
        tts.setSpeechRate(SPEECH_RATE)
        // The engine asked for; if it can't bind, the framework may quietly use another.
        description = "${engine ?: tts.defaultEngine}, ${voice?.name ?: locale.toLanguageTag()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) = finishIfLast(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finishIfLast(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = finishIfLast(utteranceId)
        })
        return speaks
    }

    /** An installed, offline voice in the watch's English if there is one, else any installed English voice. */
    private fun bestVoice(tts: TextToSpeech): Voice? {
        val usable = runCatching { tts.voices }.getOrNull().orEmpty().filter { voice ->
            voice.locale.language == "en" &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty() &&
                !voice.isNetworkConnectionRequired
        }
        return usable
            .sortedWith(
                compareByDescending<Voice> { it.locale.country == locale.country }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
            )
            .firstOrNull()
    }

    /** Speaks a whole reply. */
    fun speak(text: String, onDone: () -> Unit) {
        begin(onDone)
        SpokenText.sentences(text).forEach(::add)
        end()
    }

    /** Starts speaking a reply that is still arriving, stopping anything already being said. */
    fun begin(onDone: () -> Unit) {
        stop()
        onFinished = onDone
        streamOpen = true
    }

    /** Queues one sentence of the reply begun with [begin]. */
    fun add(sentence: String) {
        if (onFinished == null || sentence.isBlank() || failed) return
        val tts = tts
        if (!ready || tts == null) {
            waiting += sentence
            return
        }
        holdAudio()
        val id = "u${++counter}"
        // A refused sentence gets no callback, so it mustn't become the one being waited for.
        if (tts.speak(sentence, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS) {
            lastUtteranceId = id
        } else {
            finishIfIdle()
        }
    }

    /** The reply is complete: [begin]'s callback runs once the last sentence has been spoken. */
    fun end() {
        streamOpen = false
        finishIfIdle()
    }

    fun stop() {
        onFinished = null
        streamOpen = false
        waiting.clear()
        lastUtteranceId = null
        if (ready) tts?.stop()
        releaseAudio()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun finishIfLast(utteranceId: String?) {
        main.post {
            if (utteranceId == null || utteranceId != lastUtteranceId) return@post
            lastUtteranceId = null
            // Waiting for more of a reply (a tool may be running): don't hold the music down meanwhile.
            if (streamOpen) releaseAudio()
            finishIfIdle()
        }
    }

    /** Runs the callback once nothing is being said, waiting or still to come. */
    private fun finishIfIdle() {
        if (streamOpen || lastUtteranceId != null || (waiting.isNotEmpty() && !failed)) return
        val done = onFinished ?: return
        onFinished = null
        releaseAudio()
        main.post(done)
    }

    private fun holdAudio() {
        if (audioHeld) return
        audioHeld = true
        audioManager.requestAudioFocus(focusRequest)
        raiseQuietVolume()
    }

    private fun releaseAudio() {
        if (!audioHeld) return
        audioHeld = false
        audioManager.abandonAudioFocusRequest(focusRequest)
        restoreQuietVolume()
    }

    /** A reply nobody can hear is no reply: lift a low (but not muted) media volume while speaking. */
    private fun raiseQuietVolume() {
        if (restoreVolume != null) return
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val floor = (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * MIN_VOLUME_FRACTION).toInt()
        if (current in 1 until floor) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, floor, 0) }
                .onSuccess {
                    restoreVolume = current
                    raisedTo = floor
                }
        }
    }

    /** Puts the volume back, unless the wearer changed it meanwhile; their choice then stands. */
    private fun restoreQuietVolume() {
        val original = restoreVolume ?: return
        val raised = raisedTo
        restoreVolume = null
        raisedTo = null
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != raised) return
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0) }
    }

    private fun installed(pkg: String): Boolean = try {
        appContext.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private companion object {
        const val GOOGLE_TTS = "com.google.android.tts"
        const val SPEECH_RATE = 1.1f
        const val MIN_VOLUME_FRACTION = 0.4f
    }
}
