package dev.itayp.tasker.channel.whatsapp

import tools.jackson.databind.JsonNode

/**
 * A one-line, log-safe description of a webhook delivery: which fields changed, for which of
 * our numbers, and the ids and types of the messages and statuses it carries. Never phone
 * numbers, names, user ids or message text (`docs/MESSAGING-CHANNELS.md`, section 2).
 *
 * Shape (`entry[].changes[].value`): `metadata.phone_number_id`, `messages[]` with `id` and
 * `type`, `statuses[]` with `id` and `status`. One delivery can batch several entries and changes.
 */
object WhatsAppWebhookSummary {

    fun describe(payload: JsonNode, ourPhoneNumberId: String): String {
        val changes = payload.path("entry").flatMap { entry -> entry.path("changes").toList() }
        val described = changes.joinToString("; ") { describeChange(it, ourPhoneNumberId) }
        return "object=${payload.path("object").asString("?")}, changes=${changes.size}" +
            if (described.isEmpty()) "" else " [$described]"
    }

    private fun describeChange(change: JsonNode, ourPhoneNumberId: String): String {
        val value = change.path("value")
        val phoneNumberId = value.path("metadata").path("phone_number_id").asString("")
        val foreign = ourPhoneNumberId.isNotBlank() && phoneNumberId.isNotEmpty() && phoneNumberId != ourPhoneNumberId
        return buildList {
            add("field=${change.path("field").asString("?")}")
            if (phoneNumberId.isNotEmpty()) add("phoneNumberId=$phoneNumberId${if (foreign) " (foreign)" else ""}")
            value.path("messages").takeIf { it.size() > 0 }?.let { messages ->
                add("messages=" + messages.joinToString(",", "[", "]") { "${it.path("id").asString("?")}:${it.path("type").asString("?")}" })
            }
            value.path("statuses").takeIf { it.size() > 0 }?.let { statuses ->
                add("statuses=" + statuses.joinToString(",", "[", "]") { "${it.path("id").asString("?")}:${it.path("status").asString("?")}" })
            }
            value.path("errors").takeIf { it.size() > 0 }?.let { errors ->
                add("errors=" + errors.joinToString(",", "[", "]") { it.path("code").asString("?") })
            }
        }.joinToString(" ")
    }
}
