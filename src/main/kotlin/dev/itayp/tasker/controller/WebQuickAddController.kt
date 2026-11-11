package dev.itayp.tasker.controller

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.ai.access.AiDisabledException
import dev.itayp.tasker.capture.QuickAddFlow
import dev.itayp.tasker.capture.QuickAddState
import dev.itayp.tasker.capture.WebQuickAddRegistry
import dev.itayp.tasker.channel.BufferedConversationChannel
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChannelType
import dev.itayp.tasker.channel.MarkdownMessageFormatter
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.BoardMembershipService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Web entry point for quick-add: free text in, drafted tasks and events out, with the same
 * confirm / adjust / clarify rounds as Telegram's `/add` (`docs/QUICKADD-CHANNELS.md`, channel 1).
 * Drives [QuickAddFlow] over plain HTTP the way [WebPlanningController] drives the planner: each
 * request runs one synchronous turn against a [BufferedConversationChannel] and returns what it
 * buffered, rendered as Markdown.
 *
 * Captures save to the board in the URL, not the user's default board. Text only for now: there is
 * no upload path, so the flow's media branches are unreachable from here.
 *
 * AI metering needs nothing here: every model call goes through the per-call gate (user opt-out,
 * tier, token budget), whose exceptions carry their own HTTP status. The board-level opt-out is not
 * part of that gate, so it is checked on every turn.
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/quick-add")
class WebQuickAddController(
    private val quickAddFlow: QuickAddFlow,
    private val registry: WebQuickAddRegistry,
    private val boardMembershipService: BoardMembershipService,
    private val aiAccessService: AiAccessService,
) {
    @PostMapping("/start")
    fun start(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @RequestBody request: QuickAddStartRequest,
    ): QuickAddTurnResponse {
        val userId = principal.userId
        requireAccess(userId, boardId)
        val text = request.text.trim()
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Text must be 1 to $MAX_TEXT_CHARS characters")
        }
        val channel = newChannel(boardId)
        val state = quickAddFlow.begin(userId, channel, text)
        return respond(userId, boardId, state, channel)
    }

    @PostMapping("/reply")
    fun reply(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
        @RequestBody request: QuickAddReplyRequest,
    ): QuickAddTurnResponse {
        val userId = principal.userId
        requireAccess(userId, boardId)
        // Nothing in progress on this board: it expired, was lost to a restart, or was replaced by
        // a capture started elsewhere. 409 tells the client to start over.
        val capture = registry.get(userId)?.takeIf { it.boardId == boardId }
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "No capture in progress")
        val text = request.text?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > MAX_TEXT_CHARS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Text must be at most $MAX_TEXT_CHARS characters")
        }
        val inbound: ChannelInbound = when {
            request.optionId != null -> ChannelInbound.Selection(request.optionId, text)
            text != null -> ChannelInbound.Text(text)
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Reply must include text or optionId")
        }
        val channel = newChannel(boardId)
        // stateOrNull: there is nothing to route to here, so a lapsed plan offer whose replacement
        // message wasn't a capture simply ends the flow.
        val state = quickAddFlow.handleInbound(userId, channel, capture.state, inbound).stateOrNull()
        return respond(userId, boardId, state, channel)
    }

    @PostMapping("/abandon")
    fun abandon(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable boardId: UUID,
    ): ResponseEntity<Void> {
        boardMembershipService.requireMember(principal.userId, boardId)
        registry.remove(principal.userId)
        return ResponseEntity.noContent().build()
    }

    private fun requireAccess(userId: UUID, boardId: UUID) {
        boardMembershipService.requireMember(userId, boardId)
        if (!aiAccessService.isAiAvailableForUser(userId)) throw AiDisabledException("AI is not available to user $userId")
        aiAccessService.requireAiEnabledForBoard(boardId)
    }

    private fun newChannel(boardId: UUID) =
        BufferedConversationChannel(ChannelType.WEB, formatter = MarkdownMessageFormatter, boardId = boardId)

    private fun respond(
        userId: UUID,
        boardId: UUID,
        state: QuickAddState?,
        channel: BufferedConversationChannel,
    ): QuickAddTurnResponse {
        if (state != null) registry.set(userId, WebQuickAddRegistry.Capture(boardId, state)) else registry.remove(userId)
        return QuickAddTurnResponse.from(state, channel.drain())
    }

    companion object {
        /** Room for a pasted list of a week's tasks, bounded for model cost. */
        const val MAX_TEXT_CHARS = 4_000
    }
}

data class QuickAddStartRequest(val text: String = "")

data class QuickAddReplyRequest(val text: String? = null, val optionId: String? = null)

data class QuickAddTurnResponse(
    /** False once the flow is over (saved, cancelled, or failed): the next message must be a new start. */
    val active: Boolean,
    /**
     * True when this capture has written to the board, so the client knows to refresh it. That is
     * the case once the flow is past the confirmation card: either it ended (which also covers a
     * cancel, where the refresh is merely wasted), or it moved on to the plan offer.
     */
    val boardChanged: Boolean,
    val messages: List<RenderedMessage>,
) {
    companion object {
        fun from(state: QuickAddState?, messages: List<ChannelMessage>) = QuickAddTurnResponse(
            active = state != null,
            boardChanged = state == null || state is QuickAddState.AwaitingPlanDay || state is QuickAddState.AwaitingPlanTime,
            messages = messages.map(RenderedMessage::from),
        )
    }
}
