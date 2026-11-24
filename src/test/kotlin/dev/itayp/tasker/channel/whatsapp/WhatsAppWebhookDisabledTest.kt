package dev.itayp.tasker.channel.whatsapp

import dev.itayp.tasker.config.SecurityConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** Enabled without an app secret counts as off: nothing could be authenticated. */
@WebMvcTest(WhatsAppWebhookController::class)
@Import(SecurityConfiguration::class)
@TestPropertySource(properties = ["tasker.whatsapp.enabled=true", "tasker.whatsapp.verify-token=token"])
class WhatsAppWebhookDisabledTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `answers 404 to the handshake and to deliveries`() {
        mockMvc.perform(
            get(WhatsAppWebhookController.PATH)
                .param("hub.mode", "subscribe")
                .param("hub.verify_token", "token")
                .param("hub.challenge", "1"),
        ).andExpect(status().isNotFound)
        mockMvc.perform(post(WhatsAppWebhookController.PATH).content("{}")).andExpect(status().isNotFound)
    }
}
