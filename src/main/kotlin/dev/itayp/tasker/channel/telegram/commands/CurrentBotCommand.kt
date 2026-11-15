package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.telegram.ConditionalOnTelegramBot
import dev.itayp.tasker.planning.PlanRecapRenderer
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
/**
 * Renders the user's current weekly plan over Telegram: the scheduled tasks with their slots, then
 * the assistant's notes on the week. Mirrors what the web "Current Plan" drawer shows, via
 * [PlanRecapRenderer].
 */
@Component
@ConditionalOnTelegramBot
class CurrentBotCommand(
    private val planningSessionService: PlanningSessionService,
    private val recapRenderer: PlanRecapRenderer,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
) : BotCommandHandler {

    override val command = "current"

    override val description = "Show your current weekly plan"

    override fun handle(context: BotCommandContext) {
        val locale = userSettingsService.getLocale(context.userId)
        val plan = planningSessionService.findCurrentPlan(context.userId)

        if (plan?.id == null) {
            context.channel.send(ChannelMessage.Text(
                messageSource.getMessage("command.current.empty", null, locale)
            ))
            return
        }

        val statusKey = if (plan.status == PlanningSessionStatus.ACTIVE) {
            "command.current.status.active"
        } else {
            "command.current.status.completed"
        }
        val statusLabel = messageSource.getMessage(statusKey, null, locale)

        val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val weekStart = plan.weekStart
        val weekEnd = weekStart.plusDays(6)
        val period = "${weekStart.format(fmt)} - ${weekEnd.format(fmt)}"

        val formatter = context.channel.formatter
        val header = formatter.bold(statusLabel) + "\n" + formatter.italic(period)
        val taskBlock = recapRenderer.renderTasks(plan, formatter, locale)
            ?: messageSource.getMessage("command.current.tasks.empty", null, locale)
        val notesBlock = recapRenderer.renderNotes(plan, formatter, locale)
        val body = listOfNotNull(header, taskBlock, notesBlock).joinToString(separator = "\n\n")

        context.channel.send(ChannelMessage.Text(body))
    }

}
