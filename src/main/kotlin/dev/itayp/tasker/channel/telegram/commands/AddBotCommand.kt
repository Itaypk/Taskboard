package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.capture.QuickAddFlow
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.telegram.QuickAddRegistry
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component

/**
 * `/add [text]` — quick-capture a backlog task. With text, drafts immediately; bare `/add` asks for
 * the task. The conversational draft/adjust/clarify loop lives in [QuickAddFlow]; this handler only
 * opens it and stores the resulting per-chat state.
 *
 * During an active planning session we decline and redirect: the assistant already exposes the same
 * capability in-session (the `suggest_task` / `create_task` tools), so there's no need to juggle two
 * concurrent flows for the same chat.
 */
@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class AddBotCommand(
    private val quickAddFlow: QuickAddFlow,
    private val quickAddRegistry: QuickAddRegistry,
    private val orchestrator: WeeklyPlanningOrchestrator,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
) : BotCommandHandler {

    override val command = "add"

    override val description = "Quickly add a task"

    override fun handle(context: BotCommandContext) {
        val activeSessionId = context.sessionRegistry.get(context.chatId)
        if (activeSessionId != null && orchestrator.phase(activeSessionId) != null) {
            val locale = userSettingsService.getLocale(context.userId)
            context.channel.send(ChannelMessage.Text(messageSource.getMessage("quickadd.in_session", null, locale)))
            return
        }

        val next = quickAddFlow.begin(context.userId, context.channel, context.args)
        if (next != null) {
            quickAddRegistry.set(context.chatId, next)
        } else {
            quickAddRegistry.remove(context.chatId)
        }
    }
}
