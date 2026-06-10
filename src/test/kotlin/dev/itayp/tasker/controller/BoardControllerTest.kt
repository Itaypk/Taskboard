package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.model.BoardSummary
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BoardService
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
import java.time.Instant
import java.util.UUID

@WebMvcTest(BoardController::class)
@Import(SecurityConfiguration::class)
class BoardControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var boardService: BoardService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val boardId = UUID.fromString("00000000-0000-0000-0000-000000000003")

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    @Test
    fun `GET boards returns the user's boards`() {
        whenever(boardService.listBoardsForUser(userId)).thenReturn(listOf(
            BoardSummary(
                id = boardId,
                name = "My tasks",
                role = BoardRole.OWNER,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        ))

        mockMvc.perform(get("/api/v1/boards").with(authentication(auth)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(boardId.toString()))
            .andExpect(jsonPath("$[0].name").value("My tasks"))
            .andExpect(jsonPath("$[0].role").value("OWNER"))
    }

    @Test
    fun `GET boards unauthenticated returns 401`() {
        mockMvc.perform(get("/api/v1/boards"))
            .andExpect(status().isUnauthorized)
    }
}
