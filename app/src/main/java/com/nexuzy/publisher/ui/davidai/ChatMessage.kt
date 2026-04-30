package com.nexuzy.publisher.ui.davidai

/**
 * Represents a single chat message in the David AI conversation.
 *
 * @param content  The text content of the message.
 * @param isUser   True = sent by the user, False = sent by David AI.
 */
data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
