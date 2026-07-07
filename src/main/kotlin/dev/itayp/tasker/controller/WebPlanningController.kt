package dev.itayp.tasker.controller

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.channel.BufferedConversationChannel
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.MarkdownMessageFormatter
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.planning.PlanningTranscriptService
import dev.itayp.tasker.planning.WeekOffset
import dev.itayp.tasker.planning.WeekResolver
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import dev.itayp.tasker.security.TaskerPrincipal
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Production web entry point for the weekly planning assistant. Drives the same
 * [WeeklyPlanningOrchestrator] as the Telegram channel, but over plain HTTP using a
 * [BufferedConversationChannel]: each request runs one synchronous orchestrator turn and returns
 * every message it buffered. The browser holds the returned session id and passes it back on the
 * next turn — there is no chat-id registry like Telegram's because the orchestrator already keys
 * all cross-request state by session id.
 *
 * Unlike [DevPlanningController] this is not profile-gated and goes through normal session auth +
 * CSRF (only `/api/dev/<**>` is CSRF-exempt). Messages render as Markdown for the frontend's
 * `MarkdownRenderer`.
 */
@RestController
@RequestMapping("/api/v1/planning")
class WebPlanningController(
    private val orchestrator: WeeklyPlanningOrchestrator,
    private val planningSessionService: PlanningSessionService,
    private val transcriptService: PlanningTranscriptService,
    private val userSettingsService: UserSettingsService,
    private val aiAccessService: AiAccessService,
    private val clock: Clock,
) {
    private fun newChannel() = BufferedConversationChannel(formatter = MarkdownMessageFormatter)

    /**
     * Restores a session's full transcript so a reloaded browser can continue where it left off.
     * Reconstructs the conversation from stored messages (the React-only transcript is lost on
     * reload). Returns 409 when the orchestrator no longer holds the session in memory (e.g. after a
     * server restart) so the client falls back to the entry screen — persisting/rehydrating that
     * state is deferred to phase 2b.
     */
    @GetMapping("/{sessionId}")
    fun transcript(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable sessionId: UUID,
    ): PlanningTranscriptResponse {
        requireOwnership(principal.userId, sessionId)
        val phase = orchestrator.phase(sessionId)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Planning session is no longer active")
        val conversationId = orchestrator.conversationId(sessionId)
        val messages = if (conversationId != null) {
            val reconstructed = transcriptService.reconstruct(conversationId)
            // The post-finalize context-block proposal is an orchestrator-owned Choice (not part of the
            // AI transcript), so re-append it when the session is parked on that question.
            val proposal = orchestrator.pendingContextProposalChoice(sessionId, MarkdownMessageFormatter)
            if (proposal != null) reconstructed + TranscriptMessage.assistant(proposal) else reconstructed
        } else {
            // No AI conversation yet: the session is either awaiting the capacity reply or in the
            // pre-session reconciliation sweep. Re-render whichever question is currently pending so a
            // reloaded browser can continue (neither is part of the AI transcript).
            val pending = orchestrator.pendingReconcileChoice(sessionId)
                ?: orchestrator.capacityChoice(sessionId, MarkdownMessageFormatter)
            pending?.let { listOf(TranscriptMessage.assistant(it)) } ?: emptyList()
        }
        return PlanningTranscriptResponse(sessionId, phase.name, messages)
    }

    /**
     * Tells the frontend what to offer when the planning drawer opens: a resumable in-flight
     * session, a completed plan to revise, and always the this-week / next-week options to start
     * fresh. Mirrors the three cases of the Telegram `/plan` command, but as data the React drawer
     * renders into explicit buttons instead of a chat choice.
     */
    @GetMapping("/entry")
    fun entry(@AuthenticationPrincipal principal: TaskerPrincipal): PlanningEntryResponse {
        val userId = principal.userId
        // Only treat an ACTIVE session as resumable when the orchestrator still holds its in-memory
        // state; after a restart that state is gone and `start` cleanly reuses the DB row instead.
        val activeSession = planningSessionService.findActiveSession(userId)
        val resumableSessionId = activeSession?.id?.takeIf { orchestrator.phase(it) != null }

        val completedPlan = if (activeSession == null) {
            planningSessionService.findCurrentPlan(userId)
                ?.takeIf { it.status == PlanningSessionStatus.COMPLETED && it.summary != null }
        } else null

        return PlanningEntryResponse(
            activeSessionId = resumableSessionId,
            completedPlanSummary = completedPlan?.summary,
            revisableSessionId = completedPlan?.id,
            thisWeek = weekOption(userId, WeekOffset.CURRENT),
            nextWeek = weekOption(userId, WeekOffset.NEXT),
            aiAvailable = aiAccessService.isAiAvailableForUser(userId),
        )
    }

    @PostMapping("/start")
    fun start(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestBody request: StartPlanningRequest,
    ): PlanningTurnResponse {
        val offset = parseOffset(request.offset)
        val weekStart = resolveWeekStart(principal.userId, offset)
        val channel = newChannel()
        val sessionId = orchestrator.start(principal.userId, channel, weekStart)
        return PlanningTurnResponse.from(sessionId, orchestrator.phase(sessionId), channel.drain())
    }

    @PostMapping("/{sessionId}/reply")
    fun reply(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable sessionId: UUID,
        @RequestBody request: PlanningReplyRequest,
    ): PlanningTurnResponse {
        requireOwnership(principal.userId, sessionId)
        // No in-memory state means the session was never started in this process or was lost to a
        // restart. 409 tells the frontend to drop its stale id and return to the entry screen.
        if (orchestrator.phase(sessionId) == null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Planning session is no longer active")
        }
        val inbound: ChannelInbound = when {
            request.optionId != null -> ChannelInbound.Selection(request.optionId, request.text)
            request.text != null -> ChannelInbound.Text(request.text)
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Reply must include text or optionId")
        }
        val channel = newChannel()
        orchestrator.handleInbound(sessionId, inbound, channel)
        return PlanningTurnResponse.from(sessionId, orchestrator.phase(sessionId), channel.drain())
    }

    @PostMapping("/{sessionId}/revise")
    fun revise(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable sessionId: UUID,
    ): PlanningTurnResponse {
        val channel = newChannel()
        try {
            orchestrator.startRevision(principal.userId, sessionId, channel)
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message)
        }
        return PlanningTurnResponse.from(sessionId, orchestrator.phase(sessionId), channel.drain())
    }

    @PostMapping("/{sessionId}/abandon")
    fun abandon(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable sessionId: UUID,
    ): ResponseEntity<Void> {
        orchestrator.abandon(principal.userId, sessionId)
        return ResponseEntity.noContent().build()
    }

    private fun requireOwnership(userId: UUID, sessionId: UUID) {
        if (planningSessionService.findById(userId, sessionId) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Planning session not found")
        }
    }

    private fun parseOffset(raw: String): WeekOffset =
        runCatching { WeekOffset.valueOf(raw.uppercase()) }
            .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown week offset: $raw") }

    private fun resolveWeekStart(userId: UUID, offset: WeekOffset): LocalDate {
        val settings = userSettingsService.getOrCreate(userId)
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
        val today = LocalDate.now(clock.withZone(zone))
        return WeekResolver.resolveWeekStart(today, WeekResolver.parseWeekStartDay(settings.weekStartDay), offset)
    }

    private fun weekOption(userId: UUID, offset: WeekOffset): WeekOption {
        val start = resolveWeekStart(userId, offset)
        return WeekOption(start.toString(), start.plusDays(6).toString())
    }
}

data class StartPlanningRequest(val offset: String)

data class PlanningReplyRequest(
    val text: String? = null,
    val optionId: String? = null,
)

data class WeekOption(val weekStart: String, val weekEnd: String)

data class PlanningEntryResponse(
    val activeSessionId: UUID?,
    val completedPlanSummary: String?,
    val revisableSessionId: UUID?,
    val thisWeek: WeekOption,
    val nextWeek: WeekOption,
    /**
     * False when AI features are unavailable to the caller — either they themselves have opted
     * out, or every board they belong to has a co-member who opted out. The SPA disables Start /
     * Resume / Revise buttons in that state.
     */
    val aiAvailable: Boolean,
)
