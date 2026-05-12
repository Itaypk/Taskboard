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
    fun `unknown top-level route is forwarded to index html`() {
        mockMvc.perform(get("/unknown-page"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `terms route is forwarded to index html`() {
        mockMvc.perform(get("/terms"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `privacy route is forwarded to index html`() {
        mockMvc.perform(get("/privacy"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `nested path is forwarded to index html`() {
        mockMvc.perform(get("/some/nested/path"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `path with file extension is not forwarded to index html`() {
        mockMvc.perform(get("/assets/app.js"))
            .andExpect(forwardedUrl(null))
    }

    @Test
    fun `authenticated user on unknown route is forwarded to index html`() {
        mockMvc.perform(get("/dashboard").with { req ->
            req.addHeader("Cookie", "SESSION=fake")
            req
        })
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }
}
