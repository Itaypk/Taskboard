package dev.itayp.tasker.channel.messaging

import dev.itayp.tasker.channel.AttachmentIntake
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelType
import dev.itayp.tasker.channel.ConversationChannel

/**
 * One inbound message from a messaging channel, as a provider's webhook controller hands it to
 * [MessagingCaptureService.accept] (`docs/MESSAGING-CHANNELS.md`, section 1).
 *
 * @param eventId the provider's message id, the de-duplication key: providers redeliver.
 * @param senderId the chat account's stable id (a WhatsApp business-scoped user id). Never logged.
 * @param senderDisplay what Settings shows for the account once linked (a phone number), when the
 *   provider sent one. Never logged.
 * @param reply a channel addressed back to the sender.
 */
data class InboundEvent(
    val channel: ChannelType,
    val eventId: String,
    val senderId: String,
    val senderDisplay: String?,
    val content: Content,
    val reply: ConversationChannel,
) {
    sealed interface Content {
        data class Text(val text: String) : Content

        /** A tap on a button or list row: the [ChannelMessage.Choice] option's id. */
        data class Selection(val optionId: String) : Content

        /**
         * A photo, voice note or file. [fetch] downloads it, and runs only on the worker after the
         * link and AI gates, so a stranger's attachment costs no download.
         */
        class Media(val fetch: () -> MediaFetch) : Content

        /** Something we don't capture from (a sticker, a location, a video). */
        data class Unsupported(val type: String) : Content
    }

    /** Never print the sender (data classes include every field in toString). */
    override fun toString(): String = "InboundEvent(channel=$channel, eventId=$eventId, content=${content::class.simpleName})"
}

/** What [InboundEvent.Content.Media.fetch] came back with. */
sealed interface MediaFetch {
    data class Fetched(val media: ChannelInbound.Media) : MediaFetch
    data class Rejected(val reason: AttachmentIntake.Rejection) : MediaFetch
}

/** One chat on one channel: the unit of ordering and of stored capture state. */
data class ConversationKey(val channel: ChannelType, val senderId: String) {
    /** Never print the sender: [dev.itayp.tasker.channel.KeyedSerialExecutor] logs its keys. */
    override fun toString(): String = "ConversationKey(channel=$channel)"
}
