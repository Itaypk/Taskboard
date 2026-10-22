package dev.itayp.tasker.channel.inboundmail

import dev.itayp.tasker.config.InboundMailProperties
import dev.itayp.tasker.config.SecurityConfiguration
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(InboundMailController::class)
@Import(SecurityConfiguration::class)
class InboundMailControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @MockitoBean lateinit var receiver: InboundMailReceiver
    @MockitoBean lateinit var properties: InboundMailProperties

    private val token = "t".repeat(40)
    private val raw = "From: a@example.org\r\nSubject: Hi\r\n\r\nHi\r\n".toByteArray()

    private fun deliver(withToken: String? = token) = mockMvc.perform(
        post("/internal/inbound-mail")
            .contentType("message/rfc822")
            .header("X-Envelope-From", "<a@example.org>")
            .header("X-Envelope-To", "<tasks.k7f3x5q2mz@m.example.com>")
            .apply { if (withToken != null) header(InboundMailController.TOKEN_HEADER, withToken) }
            .content(raw),
    )

    private fun active() {
        whenever(properties.active).thenReturn(true)
        whenever(properties.deliveryToken).thenReturn(token)
    }

    @Test
    fun `accepts a delivery without a session or CSRF token, and passes the bare envelope addresses on`() {
        active()
        whenever(receiver.receive(any(), any(), any())).thenReturn(ReceiveOutcome.QUEUED)

        deliver().andExpect(status().isAccepted)
        verify(receiver).receive(eq("a@example.org"), eq("tasks.k7f3x5q2mz@m.example.com"), eq(raw))
    }

    @Test
    fun `maps outcomes the MTA should drop to 4xx`() {
        active()
        whenever(receiver.receive(any(), any(), any())).thenReturn(ReceiveOutcome.UNKNOWN_RECIPIENT, ReceiveOutcome.MALFORMED)

        deliver().andExpect(status().isNotFound)
        deliver().andExpect(status().isBadRequest)
    }

    @Test
    fun `refuses a missing or wrong delivery token`() {
        active()

        deliver(withToken = null).andExpect(status().isUnauthorized)
        deliver(withToken = "wrong").andExpect(status().isUnauthorized)
        verify(receiver, never()).receive(any(), any(), any())
    }

    @Test
    fun `answers 503 while the feature is off, so the MTA keeps the mail`() {
        whenever(properties.active).thenReturn(false)

        deliver().andExpect(status().isServiceUnavailable)
        verify(receiver, never()).receive(any(), any(), any())
    }
}
