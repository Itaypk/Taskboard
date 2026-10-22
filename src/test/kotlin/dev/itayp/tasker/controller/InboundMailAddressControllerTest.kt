package dev.itayp.tasker.controller

import dev.itayp.tasker.config.InboundMailProperties
import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.InboundMailAddress
import dev.itayp.tasker.service.InboundMailAddressService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
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

@WebMvcTest(InboundMailAddressController::class)
@Import(SecurityConfiguration::class)
class InboundMailAddressControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @MockitoBean lateinit var inboundMailAddressService: InboundMailAddressService
    @MockitoBean lateinit var properties: InboundMailProperties

    private val userId = UUID.randomUUID()
    private val auth = authentication(
        UsernamePasswordAuthenticationToken(TaskerPrincipal(userId), null, listOf(SimpleGrantedAuthority("ROLE_USER"))),
    )
    private val address = InboundMailAddress("tasks.k7f3x5q2mz@m.example.com", Instant.parse("2026-10-22T12:00:00Z"))

    @Test
    fun `shows the address, or nulls when there is none`() {
        whenever(properties.active).thenReturn(true)
        whenever(inboundMailAddressService.getAddress(userId)).thenReturn(address, null)

        mockMvc.perform(get("/api/v1/inbound-mail-address").with(auth))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.address").value(address.address))
            .andExpect(jsonPath("$.createdAt").value("2026-10-22T12:00:00Z"))
        mockMvc.perform(get("/api/v1/inbound-mail-address").with(auth))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.address").doesNotExist())
    }

    @Test
    fun `creates or rotates, and turns off`() {
        whenever(properties.active).thenReturn(true)
        whenever(inboundMailAddressService.rotate(userId)).thenReturn(address)

        mockMvc.perform(post("/api/v1/inbound-mail-address").with(auth).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.address").value(address.address))
        mockMvc.perform(delete("/api/v1/inbound-mail-address").with(auth).with(csrf()))
            .andExpect(status().isNoContent)
        verify(inboundMailAddressService).revoke(userId)
    }

    @Test
    fun `requires CSRF to rotate`() {
        whenever(properties.active).thenReturn(true)

        mockMvc.perform(post("/api/v1/inbound-mail-address").with(auth))
            .andExpect(status().isForbidden)
        verify(inboundMailAddressService, never()).rotate(any())
    }

    @Test
    fun `answers 404 everywhere while the feature is off`() {
        whenever(properties.active).thenReturn(false)

        mockMvc.perform(get("/api/v1/inbound-mail-address").with(auth)).andExpect(status().isNotFound)
        mockMvc.perform(post("/api/v1/inbound-mail-address").with(auth).with(csrf())).andExpect(status().isNotFound)
        mockMvc.perform(delete("/api/v1/inbound-mail-address").with(auth).with(csrf())).andExpect(status().isNotFound)
        verify(inboundMailAddressService, never()).rotate(any())
    }
}
