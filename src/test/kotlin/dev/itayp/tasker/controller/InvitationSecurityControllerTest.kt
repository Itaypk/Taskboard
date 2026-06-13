package dev.itayp.tasker.controller

import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.model.AcceptedInvitation
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.model.InvitationPreview
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BoardInvitationService
import dev.itayp.tasker.service.BoardMemberService
import dev.itayp.tasker.service.BoardOwnerRequiredException
import dev.itayp.tasker.service.SoleMemberException
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

@WebMvcTest(InvitationController::class, BoardMemberController::class)
@Import(SecurityConfiguration::class)
class InvitationSecurityControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean lateinit var invitationService: BoardInvitationService
    @MockitoBean lateinit var boardMemberService: BoardMemberService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val boardId = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val auth = authentication(
        UsernamePasswordAuthenticationToken(TaskerPrincipal(userId), null, listOf(SimpleGrantedAuthority("ROLE_USER"))),
    )

    @Test
    fun `invitation preview is reachable without authentication`() {
        whenever(invitationService.preview("tok"))
            .thenReturn(InvitationPreview("Home", "Alice", Instant.parse("2026-06-20T00:00:00Z")))

        mockMvc.perform(get("/api/v1/invitations/tok"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.boardName").value("Home"))
            .andExpect(jsonPath("$.inviterName").value("Alice"))
    }

    @Test
    fun `accept requires authentication`() {
        mockMvc.perform(post("/api/v1/invitations/tok/accept").with(csrf()))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `accept succeeds for an authenticated user`() {
        whenever(invitationService.accept(eq("tok"), eq(userId))).thenReturn(AcceptedInvitation(boardId, "Home"))

        mockMvc.perform(post("/api/v1/invitations/tok/accept").with(auth).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.boardId").value(boardId.toString()))
    }

    @Test
    fun `listing members requires authentication`() {
        mockMvc.perform(get("/api/v1/boards/$boardId/members"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `members list is returned for an authenticated member`() {
        whenever(boardMemberService.listMembers(userId, boardId)).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/boards/$boardId/members").with(auth))
            .andExpect(status().isOk)
    }

    @Test
    fun `setting a role as a non-owner returns 403`() {
        doThrow(BoardOwnerRequiredException())
            .whenever(boardMemberService).setRole(eq(userId), eq(boardId), any(), eq(BoardRole.OWNER))

        mockMvc.perform(
            patch("/api/v1/boards/$boardId/members/${UUID.randomUUID()}")
                .with(auth).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"role":"OWNER"}"""),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `leaving as the sole member returns 409`() {
        doThrow(SoleMemberException()).whenever(boardMemberService).leaveBoard(userId, boardId)

        mockMvc.perform(post("/api/v1/boards/$boardId/members/leave").with(auth).with(csrf()))
            .andExpect(status().isConflict)
    }
}
