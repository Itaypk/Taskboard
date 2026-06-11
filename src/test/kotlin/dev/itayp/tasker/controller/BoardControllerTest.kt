package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.model.BoardSummary
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BoardOwnerRequiredException
import dev.itayp.tasker.service.BoardService
import dev.itayp.tasker.service.LastBoardException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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

    @Test
    fun `POST boards returns 201 with the created board`() {
        whenever(boardService.createBoard(eq(userId), eq("Work")))
            .thenReturn(BoardSummary(boardId, "Work", BoardRole.OWNER, Instant.parse("2026-01-01T00:00:00Z")))

        mockMvc.perform(
            post("/api/v1/boards")
                .with(authentication(auth)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Work"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(boardId.toString()))
            .andExpect(jsonPath("$.name").value("Work"))
    }

    @Test
    fun `POST boards rejects a blank name with 400`() {
        mockMvc.perform(
            post("/api/v1/boards")
                .with(authentication(auth)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"   "}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `PATCH boards renames the board`() {
        whenever(boardService.renameBoard(eq(userId), eq(boardId), eq("Renamed")))
            .thenReturn(BoardSummary(boardId, "Renamed", BoardRole.OWNER, Instant.parse("2026-01-01T00:00:00Z")))

        mockMvc.perform(
            patch("/api/v1/boards/$boardId")
                .with(authentication(auth)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Renamed"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Renamed"))
    }

    @Test
    fun `PATCH boards by a non-owner returns 403`() {
        doThrow(BoardOwnerRequiredException()).whenever(boardService).renameBoard(any(), any(), any())

        mockMvc.perform(
            patch("/api/v1/boards/$boardId")
                .with(authentication(auth)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Renamed"}""")
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `DELETE boards returns 204`() {
        mockMvc.perform(delete("/api/v1/boards/$boardId").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE the last board returns 409`() {
        doThrow(LastBoardException()).whenever(boardService).deleteBoard(userId, boardId)

        mockMvc.perform(delete("/api/v1/boards/$boardId").with(authentication(auth)).with(csrf()))
            .andExpect(status().isConflict)
    }
}
