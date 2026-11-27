package dev.itayp.tasker.channel.whatsapp

import dev.itayp.tasker.channel.ChannelType
import dev.itayp.tasker.channel.messaging.MessagingProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles

/** Fully configured, WhatsApp is offered for linking; the next class checks the opposite. */
@SpringBootTest(
    properties = [
        "tasker.whatsapp.enabled=true",
        "tasker.whatsapp.verify-token=token",
        "tasker.whatsapp.app-secret=secret",
        "tasker.whatsapp.access-token=access",
        "tasker.whatsapp.phone-number-id=106540352242922",
    ],
)
@ActiveProfiles("dev")
class WhatsAppCaptureWiringTest(@Autowired val context: ApplicationContext) {

    @Test
    fun `offers WhatsApp as a messaging provider`() {
        assertThat(context.getBeansOfType(MessagingProvider::class.java).values.map { it.channel })
            .containsExactly(ChannelType.WHATSAPP)
    }
}

/** The webhook alone (no access token) only logs: nothing is offered for linking. */
@SpringBootTest(
    properties = [
        "tasker.whatsapp.enabled=true",
        "tasker.whatsapp.verify-token=token",
        "tasker.whatsapp.app-secret=secret",
    ],
)
@ActiveProfiles("dev")
class WhatsAppLogOnlyWiringTest(@Autowired val context: ApplicationContext) {

    @Test
    fun `offers no messaging provider and has no client`() {
        assertThat(context.getBeansOfType(MessagingProvider::class.java)).isEmpty()
        assertThat(context.getBeansOfType(WhatsAppClient::class.java)).isEmpty()
    }
}
