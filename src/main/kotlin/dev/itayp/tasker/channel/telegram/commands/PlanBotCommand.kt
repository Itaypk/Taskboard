package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class PlanBotCommand(
    private val orchestrator: WeeklyPlanningOrchestrator,
) : BotCommandHandler {

    override val command = "plan"

    override fun handle(context: BotCommandContext) {
        val existingSessionId = context.sessionRegistry.get(context.chatId)
        if (existingSessionId != null && orchestrator.phase(existingSessionId) != null) {
            context.channel.send(ChannelMessage.Text("You already have a planning session in progress."))
            return
        }
        val sessionId = orchestrator.start(context.userId, context.channel)
        context.sessionRegistry.put(context.chatId, sessionId)
    }
}
