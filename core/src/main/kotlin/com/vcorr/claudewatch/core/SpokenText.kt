package com.vcorr.claudewatch.core

/** Turns Claude's replies into text that reads well aloud, and splits it into sentences for TTS. */
object SpokenText {

    private val abbreviations = setOf(
        "e.g", "i.e", "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "approx", "no", "fig",
    )
    private const val CLOSERS = "\"')]”’"

    /** Strips markdown that TTS would read out (asterisks, hashes, list markers, link targets). */
    fun clean(text: String): String {
        var s = text.replace(Regex("```[A-Za-z0-9]*"), "")
        s = s.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        s = s.replace(Regex("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s+"), "")
        s = s.replace("*", "").replace("`", "")
        // Each line becomes a sentence, so list items aren't run together when spoken.
        return s.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { line -> if (line.last() in ".!?:;,") line else "$line." }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Splits text into sentences, keeping abbreviations such as "e.g." and decimals such as "3.5" intact. */
    fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            current.append(c)
            if (c == '.' || c == '!' || c == '?') {
                while (i + 1 < text.length && text[i + 1] in CLOSERS) {
                    i++
                    current.append(text[i])
                }
                val atEnd = i + 1 >= text.length
                val spaceFollows = !atEnd && text[i + 1].isWhitespace()
                if ((atEnd || spaceFollows) && !(c == '.' && endsWithAbbreviation(current))) {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
                    current.clear()
                }
            }
            i++
        }
        current.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    /** For a reply cut off by the token limit: everything up to the last complete sentence. */
    fun upToLastSentence(text: String): String {
        val trimmed = text.trim()
        if (trimmed.trimEnd(*CLOSERS.toCharArray()).lastOrNull()?.let { it in ".!?" } != false) return trimmed
        val parts = sentences(trimmed)
        return if (parts.size > 1) parts.dropLast(1).joinToString(" ") else trimmed
    }

    private fun endsWithAbbreviation(sentence: CharSequence): Boolean {
        val word = sentence.toString()
            .trimEnd(*CLOSERS.toCharArray())
            .removeSuffix(".")
            .takeLastWhile { !it.isWhitespace() && it != '(' }
            .lowercase()
        if (word.isEmpty()) return false
        return word in abbreviations ||
            (word.length == 1 && word[0].isLetter()) ||
            (word.contains('.') && word.length <= 5)
    }
}
