package com.vcorr.claudewatch

import java.util.Locale

/**
 * Which language the conversation is in: Finnish by default, English if the watch can't do
 * Finnish. Finnish needs both halves: a recogniser that accepts it and a voice that speaks it. Each
 * is discovered as the app runs (the first listen, the speaker starting) and only ever turned off,
 * for this run; nothing is stored, so a Finnish voice installed later is used on the next launch.
 * Main thread only.
 */
object Language {

    const val FINNISH_TAG = "fi-FI"
    val FINNISH: Locale = Locale.forLanguageTag(FINNISH_TAG)

    /** False once the speech recogniser has said it has no Finnish. */
    var finnishHeard = true

    /** False once the speaker has found no Finnish voice. */
    var finnishSpoken = true

    val finnish: Boolean get() = finnishHeard && finnishSpoken

    /** The language to ask the recogniser for. */
    fun recognitionTag(): String = if (finnish) FINNISH_TAG else SpeechInput.englishTag()

    /** Picks the Finnish or English form of something said aloud. */
    fun say(english: String, finnish: String): String = if (this.finnish) finnish else english
}
