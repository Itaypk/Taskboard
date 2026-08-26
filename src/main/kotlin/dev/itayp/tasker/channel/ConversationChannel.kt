package dev.itayp.tasker.channel

/**
 * Thin abstraction over the user-facing communication surface (Telegram today,
 * email/web/etc. tomorrow). The orchestrator only ever talks to this interface;
 * adapters render outbound messages with whatever native widgets the channel
 * supports and degrade gracefully when it doesn't.
 */
interface ConversationChannel {
    val capabilities: ChannelCapabilities

    /** Renders static text and tells the model which markup this channel supports. */
    val formatter: MessageFormatter

    fun send(message: ChannelMessage)
    fun indicateTyping() = Unit
    fun logToolCall(name: String, arguments: String) = Unit
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

    /**
     * A message carrying non-text content — a photo the user forwarded, a voice note they recorded.
     * [caption] is whatever text came alongside it (Telegram photo captions), and may be blank.
     *
     * The bytes are held in memory only for the length of the call that consumes them; they are
     * never persisted or logged (see `docs/MULTIMODAL-CAPTURE.md`).
     */
    data class Media(
        val attachments: List<InboundAttachment>,
        val caption: String? = null,
    ) : ChannelInbound
}

/** What kind of thing an [InboundAttachment] is, which decides the model modality it needs. */
enum class AttachmentKind {
    IMAGE,
    AUDIO,
}

/**
 * One inbound non-text attachment, already downloaded from the channel.
 *
 * @param mediaType the IANA media type (`image/jpeg`, `audio/ogg`, …).
 * @param format for [AttachmentKind.AUDIO], the codec/container label the AI provider expects
 *   (`ogg`, `mp3`, `wav`, …). Null for images, which are identified by [mediaType].
 */
data class InboundAttachment(
    val kind: AttachmentKind,
    val bytes: ByteArray,
    val mediaType: String,
    val format: String? = null,
) {
    // Value-semantics equals/hashCode: ByteArray's identity comparison would make two attachments
    // holding the same bytes unequal, which breaks the data-class equality tests rely on.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is InboundAttachment) return false
        return kind == other.kind &&
            bytes.contentEquals(other.bytes) &&
            mediaType == other.mediaType &&
            format == other.format
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + (format?.hashCode() ?: 0)
        return result
    }
}
