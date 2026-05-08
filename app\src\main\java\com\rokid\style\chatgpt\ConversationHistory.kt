package com.rokid.style.chatgpt

/**
 * Manages the rolling conversation history sent to the OpenAI API.
 *
 * The system prompt is always prepended at request time.
 * History is capped at [maxPairs] user+assistant round-trips so the context
 * window stays small (important for always-on AR glasses usage).
 */
class ConversationHistory(private var maxPairs: Int = 6) {

    data class Message(val role: String, val content: String)

    private val _messages = ArrayDeque<Message>()

    /** Read-only snapshot of current history (no system message). */
    val messages: List<Message> get() = _messages.toList()

    /** Add a user turn. */
    fun addUser(content: String) {
        _messages.addLast(Message("user", content))
        trim()
    }

    /** Add an assistant turn. */
    fun addAssistant(content: String) {
        _messages.addLast(Message("assistant", content))
        trim()
    }

    /** Clear all history. */
    fun clear() = _messages.clear()

    /** Update the rolling-window size (called from settings). */
    fun updateMaxPairs(newMax: Int) {
        maxPairs = newMax
        trim()
    }

    /**
     * Build the full messages array for the API request:
     * system message first, then history.
     */
    fun buildApiMessages(systemPrompt: String): List<Map<String, String>> {
        val list = mutableListOf<Map<String, String>>()
        list.add(mapOf("role" to "system", "content" to systemPrompt))
        _messages.forEach { list.add(mapOf("role" to it.role, "content" to it.content)) }
        return list
    }

    // Keep at most maxPairs complete pairs (user + assistant = 2 items per pair).
    private fun trim() {
        val maxItems = maxPairs * 2
        while (_messages.size > maxItems) {
            _messages.removeFirst()
        }
    }
}
