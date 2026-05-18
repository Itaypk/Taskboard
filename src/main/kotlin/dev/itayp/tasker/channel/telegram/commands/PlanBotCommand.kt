package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.Companion.OPTION_KEEP
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.Companion.OPTION_NEW
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.PendingConfirmation
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class PlanBotCommand(
    private val orchestrator: WeeklyPlanningOrchestrator,
    private val planningSessionService: PlanningSessionService,
    private val planConfirmationRegistry: PlanConfirmationRegistry,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
) : BotCommandHandler {

    override val command = "plan"

    override val description = "Start or review your weekly planning session"

    override fun handle(context: BotCommandContext) {
        val locale = userSettingsService.getLocale(context.userId)
        val existingSessionId = context.sessionRegistry.get(context.chatId)

        // Case 1: active session still alive in memory
        if (existingSessionId != null && orchestrator.phase(existingSessionId) != null) {
            planConfirmationRegistry.set(
                context.chatId,
                PendingConfirmation(context.userId, existingSessionId),
            )
            context.channel.send(ChannelMessage.Choice(
                prompt = messageSource.getMessage("planning.confirm.active.prompt", null, locale),
                options = listOf(
                    ChoiceOption(OPTION_KEEP, messageSource.getMessage("planning.confirm.active.continue", null, locale)),
                    ChoiceOption(OPTION_NEW, messageSource.getMessage("planning.confirm.active.abandon", null, locale)),
                ),
            ))
            return
        }

        // Case 2: completed plan exists in DB — show it and ask whether to redo
        val existingPlan = planningSessionService.findCurrentPlan(context.userId)
        if (existingPlan?.status == PlanningSessionStatus.COMPLETED && existingPlan.summary != null) {
            planConfirmationRegistry.set(
                context.chatId,
                PendingConfirmation(context.userId, existingSessionId = null),
            )
            context.channel.send(ChannelMessage.Choice(
                prompt = messageSource.getMessage(
                    "planning.confirm.completed.prompt",
                    arrayOf<Any>(existingPlan.summary!!),
                    locale,
                ),
                options = listOf(
                    ChoiceOption(OPTION_KEEP, messageSource.getMessage("planning.confirm.completed.keep", null, locale)),
                    ChoiceOption(OPTION_NEW, messageSource.getMessage("planning.confirm.completed.new", null, locale)),
                ),
            ))
            return
        }

        // Case 3: no existing plan — start normally
        val sessionId = orchestrator.start(context.userId, context.channel)
        context.sessionRegistry.put(context.chatId, sessionId)
    }

}
