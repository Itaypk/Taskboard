package dev.itayp.tasker.controller

import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import dev.itayp.tasker.channel.InMemoryConversationChannel
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import dev.itayp.tasker.security.TaskerPrincipal
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manual-test entry point for the weekly planning assistant. Lets a developer drive a
 * full session over plain HTTP without any Telegram glue. Production traffic will go
 * through a Telegram-channel adapter that uses the same [WeeklyPlanningOrchestrator].
 */
@RestController
@RequestMapping("/api/dev/planning")
@Profile("dev")
class DevPlanningController(
    private val orchestrator: WeeklyPlanningOrchestrator,
) {

    private val channels = ConcurrentHashMap<UUID, InMemoryConversationChannel>()

    @PostMapping("/start")
    fun start(@AuthenticationPrincipal principal: TaskerPrincipal): ResponseEntity<DevPlanningResponse> {
        val channel = InMemoryConversationChannel()
        val sessionId = orchestrator.start(principal.userId, channel)
        channels[sessionId] = channel
        return ResponseEntity.ok(DevPlanningResponse.from(sessionId, orchestrator.phase(sessionId), channel.drain()))
    }

    @PostMapping("/{sessionId}/reply")
    fun reply(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable sessionId: UUID,
        @RequestBody request: DevPlanningReplyRequest,
    ): ResponseEntity<DevPlanningResponse> {
        val channel = channels[sessionId]
            ?: return ResponseEntity.notFound().build()
        val inbound: ChannelInbound = when {
            request.optionId != null -> ChannelInbound.Selection(request.optionId, request.text)
            request.text != null -> ChannelInbound.Text(request.text)
            else -> return ResponseEntity.badRequest().build()
        }
        orchestrator.handleInbound(sessionId, inbound, channel)
        return ResponseEntity.ok(DevPlanningResponse.from(sessionId, orchestrator.phase(sessionId), channel.drain()))
    }

    @GetMapping("/{sessionId}")
    fun get(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable sessionId: UUID,
    ): ResponseEntity<DevPlanningResponse> {
        val channel = channels[sessionId]
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(DevPlanningResponse.from(sessionId, orchestrator.phase(sessionId), channel.drain()))
    }

    @PostMapping("/{sessionId}/abandon")
    fun abandon(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable sessionId: UUID,
    ): ResponseEntity<Void> {
        orchestrator.abandon(principal.userId, sessionId)
        channels.remove(sessionId)
        return ResponseEntity.noContent().build()
    }
}

data class DevPlanningReplyRequest(
    val text: String? = null,
    val optionId: String? = null,
)

data class DevPlanningResponse(
    val sessionId: UUID,
    val phase: String,
    val pendingMessages: List<RenderedMessage>,
) {
    companion object {
        fun from(
            sessionId: UUID,
            phase: WeeklyPlanningOrchestrator.Phase?,
            messages: List<ChannelMessage>,
        ): DevPlanningResponse = DevPlanningResponse(
            sessionId = sessionId,
            phase = phase?.name ?: "UNKNOWN",
            pendingMessages = messages.map(RenderedMessage::from),
        )
    }
}

data class RenderedMessage(
    val type: String,
    val text: String,
    val completions: List<String> = emptyList(),
    val options: List<ChoiceOption> = emptyList(),
) {
    companion object {
        fun from(msg: ChannelMessage): RenderedMessage = when (msg) {
            is ChannelMessage.Text -> RenderedMessage("text", msg.text, msg.completions)
            is ChannelMessage.Choice -> RenderedMessage("choice", msg.prompt, options = msg.options)
        }
    }
}
