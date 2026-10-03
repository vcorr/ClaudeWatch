package com.vcorr.claudewatch.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenTextTest {

    @Test
    fun stripsMarkdown() {
        val md = "## Options\n- **Tea** is calming\n- *Coffee* wakes you\nSee [docs](https://example.com)."
        assertEquals("Options. Tea is calming. Coffee wakes you. See docs.", SpokenText.clean(md))
    }

    @Test
    fun keepsPlainTextUnchanged() {
        assertEquals("It is 5 degrees. Wear a coat!", SpokenText.clean("It is 5 degrees. Wear a coat!"))
    }

    @Test
    fun splitsSentences() {
        assertEquals(
            listOf("It's sunny.", "Bring water!", "Ready?"),
            SpokenText.sentences("It's sunny. Bring water! Ready?"),
        )
    }

    @Test
    fun keepsAbbreviationsAndDecimals() {
        assertEquals(
            listOf("Use a fruit, e.g. an apple, about 3.5 cm wide.", "Dr. Smith agrees."),
            SpokenText.sentences("Use a fruit, e.g. an apple, about 3.5 cm wide. Dr. Smith agrees."),
        )
    }

    @Test
    fun keepsClosingQuoteWithSentence() {
        assertEquals(listOf("He said \"hi.\"", "Then left."), SpokenText.sentences("He said \"hi.\" Then left."))
    }

    @Test
    fun truncatedReplyEndsAtLastFullSentence() {
        assertEquals("One. Two.", SpokenText.upToLastSentence("One. Two. Thre"))
        assertEquals("Complete.", SpokenText.upToLastSentence("Complete."))
    }

    @Test
    fun completeLengthWaitsForWhitespaceAfterASentence() {
        assertEquals(0, SpokenText.completeLength("It is sunny"))
        // The full stop might yet become a decimal point.
        assertEquals(0, SpokenText.completeLength("It is 3."))
        assertEquals("It is 3.".length, SpokenText.completeLength("It is 3. Bring"))
        assertEquals("Hi! Ready? ".length - 1, SpokenText.completeLength("Hi! Ready? Go"))
    }

    @Test
    fun completeLengthKeepsAbbreviationsWhole() {
        // "e.g." is not the end of a sentence, so only the first sentence is complete.
        val text = "Eat fruit. For example, e.g. an"
        assertEquals("Eat fruit.".length, SpokenText.completeLength(text))
    }

    @Test
    fun completeLengthIncludesClosingQuotes() {
        assertEquals("He said \"stop.\"".length, SpokenText.completeLength("He said \"stop.\" Then"))
    }
}
