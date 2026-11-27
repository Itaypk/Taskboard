package dev.itayp.tasker.controller

import dev.itayp.tasker.channel.ChannelType
import dev.itayp.tasker.channel.messaging.ChannelLinkNotAllowedException
import dev.itayp.tasker.channel.messaging.ChannelLinkService
import dev.itayp.tasker.channel.messaging.ChannelLinkView
import dev.itayp.tasker.channel.messaging.LinkCode
import dev.itayp.tasker.channel.messaging.LinkInstructions
import dev.itayp.tasker.channel.messaging.MessagingProvider
import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.security.TaskerPrincipal
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(ChannelLinkController::class)
@Import(SecurityConfiguration::class, ChannelLinkControllerTest.FakeProvider::class)
class ChannelLinkControllerTest {

    /** Stands in for the WhatsApp adapter, which registers itself only when configured. */
    @TestConfiguration
    class FakeProvider {
        @Bean
        fun whatsApp() = object : MessagingProvider {
            override val channel = ChannelType.WHATSAPP
            override fun linkInstructions(code: String) = LinkInstructions("https://wa.me/15550000000?text=$code")
        }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @MockitoBean lateinit var linkService: ChannelLinkService

    private val userId = UUID.randomUUID()
    private val auth = authentication(
        UsernamePasswordAuthenticationToken(TaskerPrincipal(userId), null, listOf(SimpleGrantedAuthority("ROLE_USER"))),
    )

    @Test
    fun `lists the offered channels with the user's link, masked`() {
        whenever(linkService.list(userId)).thenReturn(
            listOf(ChannelLinkView(ChannelType.WHATSAPP, "•••• 0978", Instant.parse("2026-11-26T12:00:00Z"))),
        )

        mockMvc.perform(get("/api/v1/channel-links").with(auth))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.channels[0].channel").value("whatsapp"))
            .andExpect(jsonPath("$.channels[0].offered").value(true))
            .andExpect(jsonPath("$.channels[0].link.display").value("•••• 0978"))
            .andExpect(jsonPath("$.channels[0].link.linkedAt").value("2026-11-26T12:00:00Z"))
    }

    @Test
    fun `issues a code with the provider's link`() {
        whenever(linkService.startLink(userId, ChannelType.WHATSAPP))
            .thenReturn(LinkCode("ABCD-EFG2", Instant.parse("2026-11-26T12:15:00Z")))

        mockMvc.perform(post("/api/v1/channel-links/whatsapp/token").with(auth).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("ABCD-EFG2"))
            .andExpect(jsonPath("$.expiresAt").value("2026-11-26T12:15:00Z"))
            .andExpect(jsonPath("$.url").value("https://wa.me/15550000000?text=ABCD-EFG2"))
    }

    @Test
    fun `refuses a code to an unclaimed account, and for a channel this instance doesn't run`() {
        whenever(linkService.startLink(userId, ChannelType.WHATSAPP)).thenThrow(ChannelLinkNotAllowedException())

        mockMvc.perform(post("/api/v1/channel-links/whatsapp/token").with(auth).with(csrf()))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/api/v1/channel-links/telegram/token").with(auth).with(csrf()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `unlinks, and needs a session and a CSRF token`() {
        mockMvc.perform(delete("/api/v1/channel-links/whatsapp").with(auth).with(csrf()))
            .andExpect(status().isNoContent)
        verify(linkService).unlink(userId, ChannelType.WHATSAPP)

        mockMvc.perform(delete("/api/v1/channel-links/whatsapp").with(auth)).andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/channel-links")).andExpect(status().isUnauthorized)
        verify(linkService, never()).startLink(any(), any())
    }
}
