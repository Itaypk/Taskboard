package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import jakarta.servlet.RequestDispatcher
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.web.error.ErrorAttributeOptions
import org.springframework.boot.web.servlet.error.ErrorAttributes
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(SpaErrorController::class)
@Import(SecurityConfiguration::class)
class SpaErrorControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var errorAttributes: ErrorAttributes

    @Test
    fun `HTML request for unknown SPA route is forwarded to index html`() {
        mockMvc.perform(
            get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/unknown-page")
                .accept(MediaType.TEXT_HTML),
        )
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `HTML request for API 404 is also forwarded to index html`() {
        mockMvc.perform(
            get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/auth/dev-login")
                .accept(MediaType.TEXT_HTML),
        )
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun `JSON request returns error attributes with correct status`() {
        whenever(errorAttributes.getErrorAttributes(any(), any<ErrorAttributeOptions>())).thenReturn(
            mapOf("status" to 404, "error" to "Not Found"),
        )

        mockMvc.perform(
            get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/tasks/missing")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Not Found"))
    }

    @Test
    fun `POST to error endpoint returns JSON for API clients`() {
        whenever(errorAttributes.getErrorAttributes(any(), any<ErrorAttributeOptions>())).thenReturn(
            mapOf("status" to 404, "error" to "Not Found"),
        )

        mockMvc.perform(
            post("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/auth/dev-login")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
    }
}
