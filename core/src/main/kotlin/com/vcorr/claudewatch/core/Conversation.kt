package com.vcorr.claudewatch.core

enum class Role { USER, ASSISTANT }

data class Turn(val role: Role, val text: String)

/** The turns of one chat, kept alternating user/assistant. */
class Conversation {

    private val turns = mutableListOf<Turn>()

    val all: List<Turn> get() = turns.toList()

    /** Adds a question, first dropping any earlier question left unanswered by an interruption or error. */
    fun addUser(text: String) {
        dropUnanswered()
        turns += Turn(Role.USER, text)
    }

    fun addAssistant(text: String) {
        turns += Turn(Role.ASSISTANT, text)
    }

    fun dropUnanswered() {
        if (turns.lastOrNull()?.role == Role.USER) turns.removeAt(turns.lastIndex)
    }

    fun clear() = turns.clear()

    /** The turns to send: at most [maxTurns], always starting with a user turn. */
    fun forRequest(maxTurns: Int = DEFAULT_MAX_TURNS): List<Turn> = trimForRequest(turns, maxTurns)

    companion object {
        const val DEFAULT_MAX_TURNS = 40

        fun trimForRequest(turns: List<Turn>, maxTurns: Int): List<Turn> {
            require(maxTurns >= 1) { "maxTurns must be at least 1" }
            var start = maxOf(0, turns.size - maxTurns)
            while (start < turns.size && turns[start].role != Role.USER) start++
            return turns.subList(start, turns.size).toList()
        }
    }
}
