package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.BacklogTaskTag
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskTagService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(BacklogTaskTagController::class)
@Import(SecurityConfiguration::class)
class BacklogTaskTagControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var tagService: BacklogTaskTagService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    @Test
    fun `GET tags returns 200 with tag list`() {
        whenever(tagService.getAllForUser(userId)).thenReturn(listOf(
            BacklogTaskTag(UUID.randomUUID(), userId, "deep-work", TagColor.VIOLET, null)
        ))

        mockMvc.perform(get("/api/v1/tags").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].label").value("deep-work"))
            .andExpect(jsonPath("$[0].colorId").value("violet"))
    }

    @Test
    fun `GET tags returns 200 with empty list`() {
        whenever(tagService.getAllForUser(userId)).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/tags").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)
    }
}
