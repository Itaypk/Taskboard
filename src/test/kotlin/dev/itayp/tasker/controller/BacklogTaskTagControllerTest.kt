package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.BacklogTaskTag
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.service.BacklogTaskTagService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
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

    @Test
    fun `GET tags returns 200 with tag list`() {
        whenever(tagService.getAllForUser("test")).thenReturn(listOf(
            BacklogTaskTag(UUID.randomUUID(), "test", "deep-work", TagColor.VIOLET, null)
        ))

        mockMvc.perform(get("/api/v1/tags"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].label").value("deep-work"))
            .andExpect(jsonPath("$[0].colorId").value("violet"))
    }

    @Test
    fun `GET tags returns 200 with empty list`() {
        whenever(tagService.getAllForUser("test")).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/tags"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)
    }
}
