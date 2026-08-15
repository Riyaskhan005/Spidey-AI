package com.riyas.offlineassistant

/**
 * A single turn in the conversation, shown as a bubble in the chat list.
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    var isStreaming: Boolean = false,
)
