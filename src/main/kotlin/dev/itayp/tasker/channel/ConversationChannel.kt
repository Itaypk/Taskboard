package dev.itayp.tasker.channel

/**
 * Thin abstraction over the user-facing communication surface (Telegram today,
 * email/web/etc. tomorrow). The orchestrator only ever talks to this interface;
 * adapters render outbound messages with whatever native widgets the channel
 * supports and degrade gracefully when it doesn't.
 */
interface ConversationChannel {
    val capabilities: ChannelCapabilities
    fun send(message: ChannelMessage)
}

data class ChannelCapabilities(
    val supportsAutocompletions: Boolean,
    val supportsInlineButtons: Boolean,
)

sealed interface ChannelMessage {
    /** Plain text reply. [completions] is a list of suggested user replies (Telegram autocompletions). */
    data class Text(
        val text: String,
        val completions: List<String> = emptyList(),
    ) : ChannelMessage

    /** A question with discrete options. Adapters render as inline buttons or numbered list. */
    data class Choice(
        val prompt: String,
        val options: List<ChoiceOption>,
    ) : ChannelMessage
}

data class ChoiceOption(val id: String, val label: String)

sealed interface ChannelInbound {
    data class Text(val text: String) : ChannelInbound
    data class Selection(val optionId: String, val freeText: String? = null) : ChannelInbound
}
