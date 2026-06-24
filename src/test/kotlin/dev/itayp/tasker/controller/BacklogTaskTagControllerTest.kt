package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.BacklogTaskTag
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TagUsage
import dev.itayp.tasker.model.request.UpdateTagRequest
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BacklogTaskTagService
import dev.itayp.tasker.service.BoardAccessDeniedException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(BacklogTaskTagController::class)
@Import(SecurityConfiguration::class)
class BacklogTaskTagControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var tagService: BacklogTaskTagService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val boardId = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val tagId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    private val basePath = "/api/v1/boards/$boardId/tags"

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private fun usage(label: String, count: Int) =
        TagUsage(BacklogTaskTag(tagId, boardId, label, TagColor.VIOLET, null), count)

    @Test
    fun `GET tags returns 200 with tag list including usage count`() {
        whenever(tagService.listTagsWithUsage(userId, boardId)).thenReturn(listOf(usage("deep-work", 4)))

        mockMvc.perform(get(basePath).with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].label").value("deep-work"))
            .andExpect(jsonPath("$[0].colorId").value("violet"))
            .andExpect(jsonPath("$[0].usageCount").value(4))
    }

    @Test
    fun `GET tags returns 200 with empty list`() {
        whenever(tagService.listTagsWithUsage(userId, boardId)).thenReturn(emptyList())

        mockMvc.perform(get(basePath).with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isEmpty)
    }

    @Test
    fun `GET tags on a board the user is not a member of returns 403`() {
        whenever(tagService.listTagsWithUsage(userId, boardId))
            .thenThrow(BoardAccessDeniedException(userId, boardId))

        mockMvc.perform(get(basePath).with(authentication(auth)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `PUT tag updates label and color`() {
        whenever(tagService.updateTag(eq(userId), eq(boardId), eq(tagId), any()))
            .thenReturn(usage("renamed", 2))

        mockMvc.perform(
            put("$basePath/$tagId").with(authentication(auth)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"renamed","colorId":"violet"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.label").value("renamed"))
            .andExpect(jsonPath("$.usageCount").value(2))
    }

    @Test
    fun `PUT tag returns 404 when the tag is missing`() {
        whenever(tagService.updateTag(eq(userId), eq(boardId), eq(tagId), any()))
            .thenThrow(NoSuchElementException("Tag not found"))

        mockMvc.perform(
            put("$basePath/$tagId").with(authentication(auth)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"x","colorId":"violet"}"""),
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `PUT tag rejects a blank label`() {
        mockMvc.perform(
            put("$basePath/$tagId").with(authentication(auth)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label":"  ","colorId":"violet"}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `DELETE tag returns 204`() {
        mockMvc.perform(delete("$basePath/$tagId").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNoContent)

        verify(tagService).deleteTag(userId, boardId, tagId)
    }

    @Test
    fun `DELETE tag returns 404 when the tag is missing`() {
        doThrow(NoSuchElementException("Tag not found"))
            .whenever(tagService).deleteTag(any(), any(), any())

        mockMvc.perform(delete("$basePath/$tagId").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNotFound)
    }
}
