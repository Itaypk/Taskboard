package dev.itayp.tasker.controller

import dev.itayp.tasker.capture.QuickAddFlow
import dev.itayp.tasker.capture.QuickAddState
import dev.itayp.tasker.channel.BufferedConversationChannel
import dev.itayp.tasker.channel.ChannelType
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.security.TaskerPrincipal
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manual-test console for the /add quick-capture flow. Drives [QuickAddFlow] over plain HTTP
 * with a per-user in-memory session, so the dev page (`/dev-quickadd.html`) can exercise the
 * full conversation — drafting, clarifying questions, Save/Adjust/Cancel, and persistence —
 * without any Telegram glue. Production traffic still goes through the Telegram channel adapter
 * using the same flow.
 */
@RestController
@RequestMapping("/api/dev/quickadd")
@Profile("dev")
class DevQuickAddController(
    private val quickAddFlow: QuickAddFlow,
) {
    private val sessions = ConcurrentHashMap<UUID, Session>()

    private data class Session(val channel: BufferedConversationChannel, var state: QuickAddState?)

    @PostMapping("/start")
    fun start(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestBody request: DevQuickAddStartRequest,
    ): ResponseEntity<DevQuickAddResponse> {
        val channel = BufferedConversationChannel(ChannelType.DEV)
        val nextState = quickAddFlow.begin(principal.userId, channel, request.text)
        val drained = channel.drain()
        if (nextState != null) {
            sessions[principal.userId] = Session(channel, nextState)
        } else {
            sessions.remove(principal.userId)
        }
        return ResponseEntity.ok(DevQuickAddResponse.from(nextState, drained))
    }

    @PostMapping("/reply")
    fun reply(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @RequestBody request: DevQuickAddReplyRequest,
    ): ResponseEntity<DevQuickAddResponse> {
        val session = sessions[principal.userId]
            ?: return ResponseEntity.notFound().build()
        val state = session.state
            ?: return ResponseEntity.badRequest().build()
        val inbound: ChannelInbound = when {
            request.optionId != null -> ChannelInbound.Selection(request.optionId, request.text)
            request.text != null -> ChannelInbound.Text(request.text)
            else -> return ResponseEntity.badRequest().build()
        }
        // stateOrNull: this dev harness has no commands to route to, so a lapsed plan offer whose
        // replacement message wasn't a capture simply ends the session.
        val nextState = quickAddFlow.handleInbound(principal.userId, session.channel, state, inbound).stateOrNull()
        val drained = session.channel.drain()
        if (nextState != null) {
            session.state = nextState
        } else {
            sessions.remove(principal.userId)
        }
        return ResponseEntity.ok(DevQuickAddResponse.from(nextState, drained))
    }

    @PostMapping("/abandon")
    fun abandon(@AuthenticationPrincipal principal: TaskerPrincipal): ResponseEntity<Void> {
        sessions.remove(principal.userId)
        return ResponseEntity.noContent().build()
    }
}

data class DevQuickAddStartRequest(val text: String? = null)

data class DevQuickAddReplyRequest(val text: String? = null, val optionId: String? = null)

data class DevQuickAddResponse(
    /** Coarse state name — useful for the page to know when the flow ended. */
    val phase: String,
    val messages: List<RenderedMessage>,
) {
    companion object {
        fun from(state: QuickAddState?, messages: List<ChannelMessage>): DevQuickAddResponse =
            DevQuickAddResponse(
                phase = state?.let { it::class.simpleName } ?: "Ended",
                messages = messages.map(RenderedMessage::from),
            )
    }
}
