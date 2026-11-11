package dev.itayp.tasker.controller

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.ai.access.AiDisabledException
import dev.itayp.tasker.capture.CaptureEntry
import dev.itayp.tasker.capture.QuickAddFlow
import dev.itayp.tasker.capture.QuickAddState
import dev.itayp.tasker.capture.WebQuickAddRegistry
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.config.SecurityConfiguration
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BoardAccessDeniedException
import dev.itayp.tasker.service.BoardMembershipService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

@WebMvcTest(WebQuickAddController::class)
@Import(SecurityConfiguration::class)
class WebQuickAddControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockitoBean
    lateinit var quickAddFlow: QuickAddFlow

    @MockitoBean
    lateinit var registry: WebQuickAddRegistry

    @MockitoBean
    lateinit var boardMembershipService: BoardMembershipService

    @MockitoBean
    lateinit var aiAccessService: AiAccessService

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000099")
    private val boardId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val base = "/api/v1/boards/$boardId/quick-add"

    private val auth = UsernamePasswordAuthenticationToken(
        TaskerPrincipal(userId),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )

    private val confirmation = QuickAddState.AwaitingConfirmation(emptyList(), "buy milk", emptyList(), Instant.EPOCH)

    private fun aiAvailable() {
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(true)
    }

    private fun postJson(path: String, body: String) = mockMvc.perform(
        post("$base$path").with(authentication(auth)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body),
    )

    @Test
    fun `start runs one turn against the board in the URL and stores the capture`() {
        aiAvailable()
        doAnswer { invocation ->
            val channel = invocation.getArgument<ConversationChannel>(1)
            channel.send(ChannelMessage.Choice("**Buy milk**", listOf(ChoiceOption(QuickAddFlow.OPTION_SAVE, "Save"))))
            confirmation
        }.whenever(quickAddFlow).begin(eq(userId), any(), eq("buy milk"), eq(boardId))

        postJson("/start", """{"text":"  buy milk "}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.boardChanged").value(false))
            .andExpect(jsonPath("$.messages[0].type").value("choice"))
            .andExpect(jsonPath("$.messages[0].options[0].id").value(QuickAddFlow.OPTION_SAVE))

        verify(registry).set(userId, WebQuickAddRegistry.Capture(boardId, confirmation))
    }

    @Test
    fun `reply advances the stored capture and clears it when the flow ends`() {
        aiAvailable()
        whenever(registry.get(userId)).thenReturn(WebQuickAddRegistry.Capture(boardId, confirmation))
        whenever(quickAddFlow.handleInbound(eq(userId), any(), eq(confirmation), any(), eq(boardId)))
            .thenReturn(CaptureEntry.Captured(null))

        postJson("/reply", """{"optionId":"${QuickAddFlow.OPTION_SAVE}"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.boardChanged").value(true))

        val inbound = argumentCaptor<ChannelInbound>()
        verify(quickAddFlow).handleInbound(eq(userId), any(), eq(confirmation), inbound.capture(), eq(boardId))
        assertEquals(QuickAddFlow.OPTION_SAVE, assertIs<ChannelInbound.Selection>(inbound.firstValue).optionId)
        verify(registry).remove(userId)
    }

    @Test
    fun `reply without a capture in progress is a conflict`() {
        aiAvailable()
        whenever(registry.get(userId)).thenReturn(null)

        postJson("/reply", """{"text":"make it oat milk"}""").andExpect(status().isConflict)
    }

    @Test
    fun `reply to a capture started on another board is a conflict`() {
        aiAvailable()
        whenever(registry.get(userId)).thenReturn(WebQuickAddRegistry.Capture(UUID.randomUUID(), confirmation))

        postJson("/reply", """{"text":"make it oat milk"}""").andExpect(status().isConflict)
        verify(quickAddFlow, never()).handleInbound(any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `blank and oversized text are rejected before any model call`() {
        aiAvailable()

        postJson("/start", """{"text":"   "}""").andExpect(status().isBadRequest)
        postJson("/start", """{"text":"${"x".repeat(WebQuickAddController.MAX_TEXT_CHARS + 1)}"}""")
            .andExpect(status().isBadRequest)
        verify(quickAddFlow, never()).begin(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a non-member is refused`() {
        doThrow(BoardAccessDeniedException(userId, boardId)).whenever(boardMembershipService).requireMember(userId, boardId)

        postJson("/start", """{"text":"buy milk"}""").andExpect(status().isForbidden)
        verify(quickAddFlow, never()).begin(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a user without AI access is refused`() {
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(false)

        postJson("/start", """{"text":"buy milk"}""").andExpect(status().isForbidden)
        verify(quickAddFlow, never()).begin(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a board with an opted-out member is refused`() {
        aiAvailable()
        doThrow(AiDisabledException("restricted")).whenever(aiAccessService).requireAiEnabledForBoard(boardId)

        postJson("/start", """{"text":"buy milk"}""").andExpect(status().isForbidden)
        verify(quickAddFlow, never()).begin(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `mutating calls need a CSRF token`() {
        mockMvc.perform(
            post("$base/start").with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON).content("""{"text":"buy milk"}"""),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `abandon drops the capture`() {
        mockMvc.perform(post("$base/abandon").with(authentication(auth)).with(csrf()))
            .andExpect(status().isNoContent)
        verify(registry).remove(userId)
    }
}
