package dev.itayp.tasker.channel.whatsapp

import dev.itayp.tasker.channel.whatsapp.WhatsAppWebhookFixtures.MESSAGE_ID
import dev.itayp.tasker.channel.whatsapp.WhatsAppWebhookFixtures.PHONE_NUMBER_ID
import dev.itayp.tasker.channel.whatsapp.WhatsAppWebhookFixtures.STATUS_ID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class WhatsAppWebhookSummaryTest {

    private val mapper = JsonMapper.builder().build()

    private fun describe(json: String, ours: String = PHONE_NUMBER_ID) = WhatsAppWebhookSummary.describe(mapper.readTree(json), ours)

    @Test
    fun `names a message's id and type, never the sender or the text`() {
        val summary = describe(WhatsAppWebhookFixtures.RECEIVED_TEXT)

        assertThat(summary).isEqualTo(
            "object=whatsapp_business_account, changes=1 [field=messages phoneNumberId=$PHONE_NUMBER_ID messages=[$MESSAGE_ID:text]]",
        )
        assertThat(summary).doesNotContain("16505551234", "Sheena", "milk", "US.1349")
    }

    @Test
    fun `names a status's id and state, never the recipient`() {
        val summary = describe(WhatsAppWebhookFixtures.READ_STATUS)

        assertThat(summary).isEqualTo(
            "object=whatsapp_business_account, changes=1 [field=messages phoneNumberId=$PHONE_NUMBER_ID statuses=[$STATUS_ID:read]]",
        )
        assertThat(summary).doesNotContain("16505551234", "US.1349")
    }

    @Test
    fun `flags a delivery for another number`() {
        assertThat(describe(WhatsAppWebhookFixtures.RECEIVED_TEXT, ours = "999")).contains("phoneNumberId=$PHONE_NUMBER_ID (foreign)")
    }

    @Test
    fun `copes with an empty or unexpected payload`() {
        assertThat(describe("{}")).isEqualTo("object=?, changes=0")
        assertThat(describe("""{"object":"page","entry":[{"changes":[{"field":"feed","value":{}}]}]}"""))
            .isEqualTo("object=page, changes=1 [field=feed]")
    }
}
