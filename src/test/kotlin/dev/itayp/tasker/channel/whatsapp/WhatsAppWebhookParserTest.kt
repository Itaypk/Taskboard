package dev.itayp.tasker.channel.whatsapp

import dev.itayp.tasker.channel.messaging.InboundEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class WhatsAppWebhookParserTest {

    private val mapper = JsonMapper.builder().build()
    private val ours = WhatsAppWebhookFixtures.PHONE_NUMBER_ID

    private val mediaRefs = mutableListOf<WhatsAppMediaRef>()

    /** Media becomes a marker naming its id, and its ref is kept for inspection. */
    private fun parse(json: String, phoneNumberId: String = ours) =
        WhatsAppWebhookParser.parse(mapper.readTree(json), phoneNumberId) { ref ->
            mediaRefs += ref
            InboundEvent.Content.Unsupported("media:${ref.mediaId}")
        }

    /** One delivery whose `value.messages` is [messages], for [phoneNumberId]. */
    private fun delivery(vararg messages: String, phoneNumberId: String = ours, contacts: String = "[]") = """
        {
          "object": "whatsapp_business_account",
          "entry": [{ "id": "1", "changes": [{
            "field": "messages",
            "value": {
              "messaging_product": "whatsapp",
              "metadata": { "display_phone_number": "15550783881", "phone_number_id": "$phoneNumberId" },
              "contacts": $contacts,
              "messages": [${messages.joinToString(",")}]
            }
          }]}]
        }
    """.trimIndent()

    private fun message(id: String, type: String, body: String, from: String? = "16505551234", userId: String? = "US.1349") =
        """{ "id": "$id", ${from?.let { "\"from\": \"$it\"," } ?: ""} ${userId?.let { "\"from_user_id\": \"$it\"," } ?: ""} "type": "$type", $body }"""

    @Test
    fun `a text message carries its id, sender and text`() {
        val parsed = parse(WhatsAppWebhookFixtures.RECEIVED_TEXT).single()

        assertThat(parsed.messageId).isEqualTo(WhatsAppWebhookFixtures.MESSAGE_ID)
        assertThat(parsed.userId).isEqualTo("US.13491208655302741918")
        assertThat(parsed.phone).isEqualTo("16505551234")
        assertThat(parsed.content).isEqualTo(InboundEvent.Content.Text("Buy milk tomorrow"))
    }

    @Test
    fun `button and list taps become selections`() {
        val parsed = parse(
            delivery(
                message("m1", "interactive", """"interactive": { "type": "button_reply", "button_reply": { "id": "save", "title": "Save" } }"""),
                message("m2", "interactive", """"interactive": { "type": "list_reply", "list_reply": { "id": "plan_day:2026-11-30", "title": "Monday" } }"""),
            ),
        )

        assertThat(parsed.map { it.content }).containsExactly(
            InboundEvent.Content.Selection("save"),
            InboundEvent.Content.Selection("plan_day:2026-11-30"),
        )
    }

    @Test
    fun `photos, voice notes and files are handed over as media refs, without downloading`() {
        val parsed = parse(
            delivery(
                message("m1", "image", """"image": { "id": "img1", "mime_type": "image/jpeg", "caption": " receipt " }"""),
                message("m2", "audio", """"audio": { "id": "aud1", "mime_type": "audio/ogg; codecs=opus", "voice": true }"""),
                message("m3", "document", """"document": { "id": "doc1", "mime_type": "application/pdf", "filename": "invite.pdf" }"""),
            ),
        )

        assertThat(parsed.map { it.content }).containsExactly(
            InboundEvent.Content.Unsupported("media:img1"),
            InboundEvent.Content.Unsupported("media:aud1"),
            InboundEvent.Content.Unsupported("media:doc1"),
        )
        assertThat(mediaRefs).containsExactly(
            WhatsAppMediaRef("img1", "image/jpeg", null, "receipt"),
            WhatsAppMediaRef("aud1", "audio/ogg; codecs=opus", null, null),
            WhatsAppMediaRef("doc1", "application/pdf", "invite.pdf", null),
        )
    }

    @Test
    fun `anything else is unsupported, named by its type`() {
        val parsed = parse(
            delivery(
                message("m1", "sticker", """"sticker": { "id": "123" }"""),
                message("m2", "video", """"video": { "id": "456" }"""),
                message("m3", "text", """"text": { "body": "  " }"""),
            ),
        )

        assertThat(parsed.map { it.content }).containsExactly(
            InboundEvent.Content.Unsupported("sticker"),
            InboundEvent.Content.Unsupported("video"),
            InboundEvent.Content.Unsupported("text"),
        )
        assertThat(mediaRefs).isEmpty()
    }

    @Test
    fun `a sender without a phone number is keyed and addressed by the business-scoped id`() {
        val parsed = parse(delivery(message("m1", "text", """"text": { "body": "hi" }""", from = null))).single()

        assertThat(parsed.userId).isEqualTo("US.1349")
        assertThat(parsed.phone).isNull()
    }

    @Test
    fun `the business-scoped id falls back to the contact's when the message lacks it`() {
        val parsed = parse(
            delivery(
                message("m1", "text", """"text": { "body": "hi" }""", userId = null),
                contacts = """[{ "wa_id": "16505551234", "user_id": "US.777" }]""",
            ),
        ).single()

        assertThat(parsed.userId).isEqualTo("US.777")
    }

    @Test
    fun `statuses, other numbers and messages without an id are skipped`() {
        assertThat(parse(WhatsAppWebhookFixtures.READ_STATUS)).isEmpty()
        assertThat(parse(WhatsAppWebhookFixtures.RECEIVED_TEXT, phoneNumberId = "999")).isEmpty()
        assertThat(parse(delivery("""{ "from": "1", "type": "text", "text": { "body": "x" } }"""))).isEmpty()
    }

    @Test
    fun `a batched delivery yields every message, in order`() {
        val batched = """
            { "entry": [
              { "changes": [{ "field": "messages", "value": { "metadata": { "phone_number_id": "$ours" },
                "messages": [${message("a", "text", """"text": { "body": "one" }""")}] } }] },
              { "changes": [{ "field": "messages", "value": { "metadata": { "phone_number_id": "$ours" },
                "messages": [${message("b", "text", """"text": { "body": "two" }""")}] } }] }
            ] }
        """.trimIndent()

        assertThat(parse(batched).map { it.messageId }).containsExactly("a", "b")
    }
}
