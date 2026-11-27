package dev.itayp.tasker.channel.whatsapp

import dev.itayp.tasker.channel.messaging.InboundEvent
import tools.jackson.databind.JsonNode

/** One user message from a webhook delivery, before it is addressed to a reply channel. */
data class WhatsAppInbound(
    val messageId: String,
    /** The sender's business-scoped user id (BSUID): the stable key of the chat account. */
    val userId: String,
    /** The sender's phone number, when Meta included it (it may not, for users with a username). */
    val phone: String?,
    val content: InboundEvent.Content,
) {
    /** Never print the sender. */
    override fun toString(): String = "WhatsAppInbound(messageId=$messageId, content=${content::class.simpleName})"
}

/**
 * A photo, voice note or file in a message: what Meta says it is, before anything is downloaded.
 * [filename] only for documents.
 */
data class WhatsAppMediaRef(val mediaId: String, val mimeType: String?, val filename: String?, val caption: String?)

/**
 * Flattens a delivery's `entry[].changes[].value.messages[]` into [WhatsAppInbound]s
 * (`docs/MESSAGING-CHANNELS.md`, section 5). Skips statuses, other fields, messages for another
 * of the app's numbers, and messages without an id or a sender. A photo, voice note or file becomes
 * whatever [media] makes of its [WhatsAppMediaRef], so the parser itself does no I/O.
 */
object WhatsAppWebhookParser {

    fun parse(
        payload: JsonNode,
        ourPhoneNumberId: String,
        media: (WhatsAppMediaRef) -> InboundEvent.Content,
    ): List<WhatsAppInbound> =
        payload.path("entry").flatMap { it.path("changes") }
            .filter { it.path("field").asString("") == "messages" }
            .map { it.path("value") }
            .filter { it.path("metadata").path("phone_number_id").asString("") == ourPhoneNumberId }
            .flatMap { value -> value.path("messages").mapNotNull { parseMessage(it, value.path("contacts"), media) } }

    private fun parseMessage(
        message: JsonNode,
        contacts: JsonNode,
        media: (WhatsAppMediaRef) -> InboundEvent.Content,
    ): WhatsAppInbound? {
        val id = message.path("id").asString("").ifEmpty { return null }
        val phone = message.path("from").asString("").ifEmpty { null }
        val userId = message.path("from_user_id").asString("").ifEmpty { null }
            ?: contacts.firstOrNull { phone != null && it.path("wa_id").asString("") == phone }
                ?.path("user_id")?.asString("")?.ifEmpty { null }
            ?: contacts.singleOrNull()?.path("user_id")?.asString("")?.ifEmpty { null }
            // Before business-scoped ids existed, the number was the only key.
            ?: phone
            ?: return null
        return WhatsAppInbound(id, userId, phone, content(message, media))
    }

    private fun content(message: JsonNode, media: (WhatsAppMediaRef) -> InboundEvent.Content): InboundEvent.Content {
        val type = message.path("type").asString("")
        return when (type) {
            "text" -> message.path("text").path("body").asString("")
                .takeIf { it.isNotBlank() }
                ?.let { InboundEvent.Content.Text(it) }
            "interactive" -> {
                val interactive = message.path("interactive")
                val reply = when (interactive.path("type").asString("")) {
                    "button_reply" -> interactive.path("button_reply")
                    "list_reply" -> interactive.path("list_reply")
                    else -> null
                }
                reply?.path("id")?.asString("")?.ifEmpty { null }?.let { InboundEvent.Content.Selection(it) }
            }
            "image", "audio", "document" -> {
                val node = message.path(type)
                node.path("id").asString("").ifEmpty { null }?.let { mediaId ->
                    media(
                        WhatsAppMediaRef(
                            mediaId = mediaId,
                            mimeType = node.path("mime_type").asString("").ifEmpty { null },
                            filename = node.path("filename").asString("").trim().ifEmpty { null },
                            caption = node.path("caption").asString("").trim().ifEmpty { null },
                        ),
                    )
                }
            }
            else -> null
        } ?: InboundEvent.Content.Unsupported(type.ifEmpty { "unknown" })
    }
}
