package com.vcorr.claudewatch

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.vcorr.claudewatch.core.SpokenText
import java.io.File
import java.util.Locale

/**
 * Speaks replies sentence by sentence with the system TTS, holding transient audio focus while it
 * talks. A reply can be spoken whole with [speak], or as it arrives with [begin], [add] for each
 * sentence, and [end]. The callback runs on the main thread once everything has been spoken (and,
 * when streaming, [end] has been called), or straight away if TTS is unavailable. Call from the
 * main thread.
 *
 * It prefers the engine with the best Finnish voice, Google's first (installed on Galaxy Watches
 * beside Samsung's default): one stored on the watch, else one that speaks over the network (the
 * watch is online whenever it talks to Claude). It speaks Finnish while the conversation is Finnish
 * ([Language]); with no Finnish voice anywhere it settles on English and turns Finnish off, and asks
 * the engine to fetch a Finnish voice it offers but hasn't installed, for next time. While it talks
 * it lifts a near-silent media volume to an audible floor and puts it back afterwards; a muted watch
 * stays muted.
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

    // Engines to try, best first: Google's (installed beside Samsung's on Galaxy Watches), then
    // the watch's default (null). The one with the best Finnish wins (see [finnishRank]); the best
    // so far is kept running meanwhile rather than restarted.
    private val candidates: List<String?> = buildList {
        if (installed(GOOGLE_TTS)) add(GOOGLE_TTS)
        // The default, unless it is Google's again.
        if (isEmpty() || defaultEngine() != GOOGLE_TTS) add(null)
    }
    private var best: TextToSpeech? = null
    private var bestEngine: String? = null
    private var bestRank = NOT_USABLE
    private var englishVoice: Voice? = null
    private var finnishVoice: Voice? = null
    // A Finnish voice the engine offers but hasn't downloaded yet.
    private var finnishToFetch: Voice? = null
    private var hasFinnish = false
    private var finnishRank = NOT_USABLE
    private var speakingFinnish: Boolean? = null
    private val onDecided = mutableListOf<() -> Unit>()

    init {
        start(0)
    }

    /**
     * Runs [action] once the speaker knows whether it can speak Finnish (see [Language]), at once
     * if it already does. The conversation's language should be read only after that.
     */
    fun whenDecided(action: () -> Unit) {
        if (ready || failed) action() else onDecided += action
    }

    private fun start(index: Int) {
        val enginePackage = candidates[index]
        engine = enginePackage
        var created: TextToSpeech? = null
        created = TextToSpeech(appContext, { status ->
            main.post {
                if (tts !== created) return@post
                val rank = if (status == TextToSpeech.SUCCESS && configure()) finnishRank else NOT_USABLE
                if (rank > bestRank) {
                    best?.shutdown()
                    best = created
                    bestEngine = enginePackage
                    bestRank = rank
                } else {
                    created?.shutdown()
                }
                val next = index + 1
                when {
                    // A stored Finnish voice can't be bettered.
                    rank == FINNISH_STORED -> settle()
                    next < candidates.size -> start(next)
                    best != null -> settle()
                    else -> fail()
                }
            }
        }, enginePackage)
        tts = created
    }

    /** Uses the best engine found, configured afresh since later candidates overwrote its voices. */
    private fun settle() {
        if (tts !== best) {
            tts = best
            engine = bestEngine
            configure()
        }
        best = null
        if (!hasFinnish) fetchFinnish()
        becomeReady()
    }

    /**
     * Asks the engine to download the Finnish voice it offers: using a voice that isn't installed
     * for synthesis starts its download (see [TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED]), so
     * a word is synthesised silently to a file. This run stays English; a later one finds the voice
     * installed. The engine may wait for Wi-Fi to download.
     */
    private fun fetchFinnish() {
        val tts = tts ?: return
        val voice = finnishToFetch ?: return
        speakingFinnish = null
        if (runCatching { tts.setVoice(voice) }.getOrNull() != TextToSpeech.SUCCESS) return
        val file = File(appContext.cacheDir, "finnish-voice.wav")
        // Its callbacks carry an id nothing waits for, so finishIfLast ignores them.
        if (runCatching { tts.synthesizeToFile("Hei.", Bundle(), file, "fetch-finnish") }.getOrNull() == TextToSpeech.SUCCESS) {
            description += ", fetching ${voice.name}"
        }
    }

    private fun becomeReady() {
        Language.finnishSpoken = hasFinnish
        ready = true
        val pending = waiting.toList()
        waiting.clear()
        pending.forEach(::add)
        finishIfIdle()
        decided()
    }

    private fun fail() {
        failed = true
        description = "unavailable"
        Language.finnishSpoken = false
        waiting.clear()
        finishIfIdle()
        decided()
    }

    private fun decided() {
        val actions = onDecided.toList()
        onDecided.clear()
        actions.forEach { it() }
    }

    /** Sets the engine up and finds its voices; false if it can speak neither English nor Finnish. */
    private fun configure(): Boolean {
        val tts = tts ?: return false
        tts.setAudioAttributes(attributes)
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
        englishVoice = bestVoice(voices, locale)
        val finnishVoices = voices.filter { it.locale.sameLanguage(Language.FINNISH) }
        val finnishSaysAvailable = available(tts, Language.FINNISH)
        // An online voice may be marked as needing a download while the language works over the
        // network; the engine's own word decides.
        finnishVoice = bestVoice(finnishVoices, Language.FINNISH)
            ?: finnishVoices.firstOrNull { it.isNetworkConnectionRequired && finnishSaysAvailable }
        finnishToFetch = finnishVoices.filter { it.notInstalled }.minByOrNull { it.isNetworkConnectionRequired }
        val hasEnglish = englishVoice != null || available(tts, locale) || available(tts, Locale.ENGLISH)
        // Taken at its word alone only by an engine that lists no Finnish voice, like Samsung's,
        // which lists none at all: one that lists Finnish only as a download would fail to speak it.
        val finnishAvailable = finnishVoices.isEmpty() && finnishSaysAvailable
        finnishRank = when {
            finnishVoice?.isNetworkConnectionRequired == false -> FINNISH_STORED
            finnishVoice != null || finnishAvailable -> FINNISH_ONLINE
            hasEnglish -> ENGLISH_ONLY
            else -> NOT_USABLE
        }
        hasFinnish = finnishRank >= FINNISH_ONLINE
        speakingFinnish = null
        tts.setSpeechRate(SPEECH_RATE)
        // The engine asked for; if it can't bind, the framework may quietly use another.
        val finnishName = finnishVoice?.let { "${it.name}${if (it.isNetworkConnectionRequired) " (online)" else ""}" }
        description = "${engine ?: tts.defaultEngine}, English: ${englishVoice?.name ?: if (hasEnglish) "default" else "none"}, " +
            "Finnish: ${finnishName ?: if (finnishAvailable) "default" else "none"}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) = finishIfLast(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finishIfLast(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = finishIfLast(utteranceId)
        })
        return finnishRank > NOT_USABLE
    }

    private fun available(tts: TextToSpeech, locale: Locale) =
        runCatching { tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)

    /** Speaks in the conversation's language: Finnish while [Language.finnish] holds, else English. */
    private fun useLanguage() {
        val tts = tts ?: return
        val finnish = Language.finnish && hasFinnish
        if (speakingFinnish == finnish) return
        speakingFinnish = finnish
        if (finnish) {
            val voice = finnishVoice
            if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) tts.setLanguage(Language.FINNISH)
        } else {
            englishVoice?.let { tts.voice = it } ?: run {
                if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.ENGLISH)
            }
        }
    }

    /**
     * The best installed voice for [wanted]'s language: one stored on the watch over one that needs
     * the network, then [wanted]'s country, quality and speed. Null if there is none.
     */
    private fun bestVoice(voices: Collection<Voice>, wanted: Locale): Voice? =
        voices
            .filter { it.locale.sameLanguage(wanted) && !it.notInstalled }
            .sortedWith(
                compareBy<Voice> { it.isNetworkConnectionRequired }
                    .thenByDescending { it.locale.sameCountry(wanted) }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
            )
            .firstOrNull()

    private val Voice.notInstalled: Boolean
        get() = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in features.orEmpty()

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
        useLanguage()
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
        best?.shutdown()
        best = null
    }

    private fun finishIfLast(utteranceId: String?) {
        main.post {
            if (utteranceId == null || utteranceId != lastUtteranceId) return@post
            lastUtteranceId = null
            // Waiting for more of a reply (a tool may be running): let the music back up if the
            // wait goes on, but not between sentences that are merely a moment apart.
            if (streamOpen) {
                main.removeCallbacks(releaseWhileWaiting)
                main.postDelayed(releaseWhileWaiting, WAIT_RELEASE_MS)
            }
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

    private val releaseWhileWaiting = Runnable {
        if (streamOpen && lastUtteranceId == null) releaseAudio()
    }

    private fun holdAudio() {
        main.removeCallbacks(releaseWhileWaiting)
        if (audioHeld) return
        audioHeld = true
        audioManager.requestAudioFocus(focusRequest)
        raiseQuietVolume()
    }

    private fun releaseAudio() {
        main.removeCallbacks(releaseWhileWaiting)
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

    /**
     * Sets the media volume at the wearer's request: [change] gets their own level (not the floor
     * this may have lifted it to while speaking) and the maximum, and returns the new level, which
     * then stands. Returns the level set.
     */
    fun setUserVolume(change: (level: Int, max: Int) -> Int): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val base = restoreVolume ?: audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val level = change(base, max).coerceIn(0, max)
        restoreVolume = null
        raisedTo = null
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
        val set = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Mid-reply, the rest of it must still be heard: lift a quiet choice again, to be restored after.
        if (audioHeld) raiseQuietVolume()
        return set
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

    private fun defaultEngine(): String? = runCatching {
        android.provider.Settings.Secure.getString(appContext.contentResolver, android.provider.Settings.Secure.TTS_DEFAULT_SYNTH)
    }.getOrNull()

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
        const val WAIT_RELEASE_MS = 1_500L

        // How well an engine serves the conversation, worst first.
        const val NOT_USABLE = 0
        const val ENGLISH_ONLY = 1
        const val FINNISH_ONLINE = 2
        const val FINNISH_STORED = 3
    }
}

// Engines name a voice's locale with two- or three-letter codes ("fi" or "fin"), so compare the
// three-letter forms.
private fun Locale.sameLanguage(other: Locale) = iso3Language() == other.iso3Language()

private fun Locale.sameCountry(other: Locale) = iso3Country() == other.iso3Country()

private fun Locale.iso3Language() = runCatching { isO3Language }.getOrDefault(language)

// A three-letter region is already in that form (and Android would misread it).
private fun Locale.iso3Country() = if (country.length == 3) country else runCatching { isO3Country }.getOrDefault(country)
