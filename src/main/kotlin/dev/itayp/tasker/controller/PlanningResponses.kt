package dev.itayp.tasker.controller

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import java.util.UUID

/**
 * A single outbound planning message rendered for an HTTP client. Shared by the dev console
 * ([DevPlanningController]) and the production web channel ([WebPlanningController]).
 *
 * - `type == "text"`: plain/markdown body in [text], with optional reply suggestions in [completions].
 * - `type == "choice"`: a question in [text] with discrete [options] the client renders as buttons.
 */
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

/**
 * The result of driving the orchestrator for one web request: the session id, its current phase,
 * and every message the assistant buffered during the (synchronous) turn.
 */
data class PlanningTurnResponse(
    val sessionId: UUID,
    val phase: String,
    val messages: List<RenderedMessage>,
) {
    companion object {
        fun from(
            sessionId: UUID,
            phase: WeeklyPlanningOrchestrator.Phase?,
            messages: List<ChannelMessage>,
        ): PlanningTurnResponse = PlanningTurnResponse(
            sessionId = sessionId,
            phase = phase?.name ?: "UNKNOWN",
            messages = messages.map(RenderedMessage::from),
        )
    }
}
