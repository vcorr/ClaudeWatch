package com.vcorr.claudewatch.core

/** Turns Claude's replies into text that reads well aloud, and splits it into sentences for TTS. */
object SpokenText {

    private val abbreviations = setOf(
        "e.g", "i.e", "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "approx", "no", "fig",
        // Finnish: esimerkiksi, muun muassa, ja niin edelleen, niin sanottu, kello, kappaletta…
        "esim", "mm", "jne", "ns", "yms", "tms", "ym", "klo", "kpl", "vrt", "ks", "ts", "puh", "tri",
    )
    private const val CLOSERS = "\"')]”’"
    private const val MID_LINE = "\u0000"

    /**
     * Strips markdown that TTS would read out (asterisks, hashes, list markers, link targets).
     * [atLineStart] is false for a piece of text that begins mid-line, as a streamed chunk can:
     * its first line is then not treated as a heading or list item.
     */
    fun clean(text: String, atLineStart: Boolean = true): String {
        // A marker no line-start pattern can match, removed again below.
        var s = if (atLineStart) text else MID_LINE + text
        s = s.replace(Regex("```[A-Za-z0-9]*"), "")
        s = s.replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        // A number with a full stop starts a list item only before a capital: "1. Tea", but not
        // the Finnish date "3. lokakuuta".
        s = s.replace(Regex("(?m)^\\s*(?:[-*•]|\\d+\\)|\\d+\\.(?=\\s+[\\p{Lu}*_]))\\s+"), "")
        s = s.replace("*", "").replace("`", "")
        // Each line becomes a sentence, so list items aren't run together when spoken.
        return s.replace(MID_LINE, "").lines()
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
                val ordinal = c == '.' && ordinalAt(text, i, current) == true
                if ((atEnd || spaceFollows) && !ordinal && !(c == '.' && endsWithAbbreviation(current))) {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
                    current.clear()
                }
            }
            i++
        }
        current.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    /**
     * For text still arriving: how much of it is finished sentences, which can be spoken now. A
     * sentence counts as finished only once whitespace follows its full stop, question or
     * exclamation mark, because more text might yet make "3." into "3.5" or "e.g" into "e.g.".
     */
    fun completeLength(text: String): Int {
        var end = 0
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
                val spaceFollows = i + 1 < text.length && text[i + 1].isWhitespace()
                // "1." alone at the start of a line numbers a list item; it doesn't end a sentence.
                // Elsewhere, as in "The answer is 4. Anything else?", it does.
                val line = text.substring(text.lastIndexOf('\n', i) + 1, i + 1).trim()
                val listNumber = c == '.' && line.dropLast(1).let { it.isNotEmpty() && it.all(Char::isDigit) }
                // A number's full stop waits to see the next word: "3. lokakuuta" goes on.
                val ordinal = c == '.' && ordinalAt(text, i, current) != false
                if (spaceFollows && !listNumber && !ordinal && !(c == '.' && endsWithAbbreviation(current))) {
                    end = i + 1
                    current.clear()
                }
            }
            i++
        }
        return end
    }

    /** Claude's text as the sentences to speak: [clean]ed, then split by [sentences]. */
    fun spokenSentences(text: String, atLineStart: Boolean = true): List<String> =
        sentences(clean(text, atLineStart))

    /** For a reply cut off by the token limit: everything up to the last complete sentence. */
    fun upToLastSentence(text: String): String {
        val trimmed = text.trim()
        if (trimmed.trimEnd(*CLOSERS.toCharArray()).lastOrNull()?.let { it in ".!?" } != false) return trimmed
        val parts = sentences(trimmed)
        return if (parts.size > 1) parts.dropLast(1).joinToString(" ") else trimmed
    }

    /**
     * Whether the full stop at [dot] ends a Finnish ordinal ("3. lokakuuta", "1. päivä") rather
     * than a sentence: a number followed by a word in lower case. Null when the next word hasn't
     * arrived yet.
     */
    private fun ordinalAt(text: String, dot: Int, sentence: CharSequence): Boolean? {
        val word = sentence.toString().trimEnd('.').takeLastWhile { !it.isWhitespace() }
        if (word.isEmpty() || !word.all(Char::isDigit)) return false
        var next = dot + 1
        while (next < text.length && text[next].isWhitespace()) next++
        if (next >= text.length) return null
        return text[next].isLowerCase()
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
