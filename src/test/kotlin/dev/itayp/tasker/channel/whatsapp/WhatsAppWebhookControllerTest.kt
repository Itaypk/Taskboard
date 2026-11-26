package dev.itayp.tasker.channel.whatsapp

import dev.itayp.tasker.channel.WebhookSignatures
import dev.itayp.tasker.config.SecurityConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(WhatsAppWebhookController::class)
@Import(SecurityConfiguration::class)
@TestPropertySource(
    properties = [
        "tasker.whatsapp.enabled=true",
        "tasker.whatsapp.verify-token=$VERIFY_TOKEN",
        "tasker.whatsapp.app-secret=$APP_SECRET",
    ],
)
class WhatsAppWebhookControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    private fun handshake(token: String?, mode: String = "subscribe") = mockMvc.perform(
        get(WhatsAppWebhookController.PATH)
            .param("hub.mode", mode)
            .param("hub.challenge", "1158201444")
            .apply { if (token != null) param("hub.verify_token", token) },
    )

    private fun deliver(body: String, signature: String? = sign(body)) = mockMvc.perform(
        post(WhatsAppWebhookController.PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (signature != null) header(WhatsAppWebhookController.SIGNATURE_HEADER, signature) }
            .content(body),
    )

    private fun sign(body: String) = "sha256=" + WebhookSignatures.hmacHex("HmacSHA256", APP_SECRET, body.toByteArray())

    @Test
    fun `echoes the challenge for the right verify token, without a session`() {
        handshake(VERIFY_TOKEN)
            .andExpect(status().isOk)
            .andExpect(content().string("1158201444"))
            .andExpect(header().doesNotExist("Set-Cookie"))
    }

    @Test
    fun `refuses the handshake for a wrong or missing token, or another mode`() {
        handshake("wrong").andExpect(status().isForbidden)
        handshake(null).andExpect(status().isForbidden)
        handshake(VERIFY_TOKEN, mode = "unsubscribe").andExpect(status().isForbidden)
    }

    @Test
    fun `acknowledges a signed delivery without a session or CSRF token`() {
        deliver(WhatsAppWebhookFixtures.RECEIVED_TEXT)
            .andExpect(status().isOk)
            .andExpect(header().doesNotExist("Set-Cookie"))
        deliver(WhatsAppWebhookFixtures.READ_STATUS).andExpect(status().isOk)
    }

    @Test
    fun `acknowledges a signed delivery it can't parse, so Meta doesn't redeliver it for a week`() {
        deliver("not json").andExpect(status().isOk)
    }

    @Test
    fun `refuses a delivery with a missing or wrong signature`() {
        val body = WhatsAppWebhookFixtures.RECEIVED_TEXT

        deliver(body, signature = null).andExpect(status().isUnauthorized)
        deliver(body, signature = "sha256=" + "0".repeat(64)).andExpect(status().isUnauthorized)
        deliver(body, signature = sign(body.replace("milk", "eggs"))).andExpect(status().isUnauthorized)
    }

    @Test
    fun `refuses an oversized delivery before checking it`() {
        val body = " ".repeat(WhatsAppWebhookController.MAX_BODY_BYTES + 1)

        deliver(body).andExpect(status().isPayloadTooLarge)
    }

    @Test
    fun `denies other methods and paths under the webhook prefix`() {
        mockMvc.perform(post("/webhooks/other")).andExpect(status().isForbidden)
    }
}

private const val VERIFY_TOKEN = "verify-token-for-tests"
private const val APP_SECRET = "app-secret-for-tests"
