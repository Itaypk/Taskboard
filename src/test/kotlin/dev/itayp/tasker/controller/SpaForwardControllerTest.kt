package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(SpaForwardController::class)
@Import(SecurityConfiguration::class)
class SpaForwardControllerTest(@Autowired val mockMvc: MockMvc) {

    @Test
    fun `email-login route is forwarded to index html`() {
        mockMvc.perform(get("/email-login"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `email-verify route is forwarded to index html`() {
        mockMvc.perform(get("/email-verify"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `settings route is forwarded to index html`() {
        mockMvc.perform(get("/settings"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `settings tab route is forwarded to index html`() {
        mockMvc.perform(get("/settings/assistant"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }
}
