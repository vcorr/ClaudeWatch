package com.vcorr.claudewatch.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationTest {

    private fun turns(n: Int) = (0 until n).map { i ->
        Turn(if (i % 2 == 0) Role.USER else Role.ASSISTANT, "t$i")
    }

    @Test
    fun shortHistoryIsSentWhole() {
        assertEquals(turns(3), Conversation.trimForRequest(turns(3), 40))
    }

    @Test
    fun trimmedHistoryStartsWithUserTurn() {
        // 7 turns, cap 4: the last four begin with an assistant turn, so that one is dropped too.
        val trimmed = Conversation.trimForRequest(turns(7), 4)
        assertEquals(Role.USER, trimmed.first().role)
        assertEquals(listOf("t4", "t5", "t6"), trimmed.map { it.text })
    }

    @Test
    fun unansweredQuestionIsReplaced() {
        val c = Conversation()
        c.addUser("first")
        c.addUser("second")
        assertEquals(listOf("second"), c.all.map { it.text })
    }

    @Test
    fun turnsAlternate() {
        val c = Conversation()
        c.addUser("q1"); c.addAssistant("a1"); c.addUser("q2")
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), c.forRequest().map { it.role })
    }

    @Test
    fun restoreKeepsAWellFormedHistory() {
        val c = Conversation()
        c.restore(
            listOf(
                Turn(Role.ASSISTANT, "orphan answer"),
                Turn(Role.USER, "q1"),
                Turn(Role.ASSISTANT, "a1"),
                Turn(Role.ASSISTANT, "duplicate answer"),
                Turn(Role.USER, "q2"),
            )
        )
        assertEquals(listOf("q1", "a1"), c.all.map { it.text })
    }

    @Test
    fun restoreReplacesWhatWasThere() {
        val c = Conversation()
        c.addUser("old"); c.addAssistant("old answer")
        c.restore(listOf(Turn(Role.USER, "q"), Turn(Role.ASSISTANT, "a")))
        assertEquals(listOf("q", "a"), c.all.map { it.text })
    }

    @Test
    fun savedChatIsFreshOnlyWithinItsAge() {
        val hour = 3_600_000L
        assertEquals(true, Conversation.isFresh(savedAtMs = 10 * hour, nowMs = 10 * hour + 1_000, maxAgeMs = hour))
        assertEquals(false, Conversation.isFresh(savedAtMs = 10 * hour, nowMs = 12 * hour, maxAgeMs = hour))
        // A clock set back after saving doesn't resurrect a chat from the future.
        assertEquals(false, Conversation.isFresh(savedAtMs = 12 * hour, nowMs = 10 * hour, maxAgeMs = hour))
    }
}
