package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * Renders the user's current weekly plan over Telegram: session summary plus the list of
 * tasks scheduled in that session. Mirrors what the web "Current Plan" drawer shows —
 * driven by the same [PlanningSessionService.findCurrentPlan] /
 * [BacklogTaskService.getTasksScheduledInSession] pair the REST controller uses.
 *
 * No per-day breakdown: the structured time slots from `submit_plan` are not persisted to
 * the DB (only to the AI transcript), so a "today / week" split isn't possible without
 * a schema change. We surface the same flat list the web UI does.
 */
@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class CurrentBotCommand(
    private val planningSessionService: PlanningSessionService,
    private val backlogTaskService: BacklogTaskService,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
) : BotCommandHandler {

    override val command = "current"

    override val description = "Show your current weekly plan"

    override fun handle(context: BotCommandContext) {
        val locale = userLocale(context)
        val plan = planningSessionService.findCurrentPlan(context.userId)

        if (plan?.id == null) {
            context.channel.send(ChannelMessage.Text(
                messageSource.getMessage("command.current.empty", null, locale)
            ))
            return
        }

        val tasks = backlogTaskService.getTasksScheduledInSession(context.userId, plan.id!!)

        val statusKey = if (plan.status == PlanningSessionStatus.ACTIVE) {
            "command.current.status.active"
        } else {
            "command.current.status.completed"
        }
        val statusLabel = messageSource.getMessage(statusKey, null, locale)

        val summaryBlock = plan.summary?.takeIf { it.isNotBlank() }
            ?.let {
                val label = messageSource.getMessage("command.current.summary.label", null, locale)
                "<b>${escapeHtml(label)}</b>\n${escapeHtml(it)}"
            }
            ?: messageSource.getMessage("command.current.summary.empty", null, locale)

        val taskBlock = if (tasks.isEmpty()) {
            messageSource.getMessage("command.current.tasks.empty", null, locale)
        } else {
            val header = messageSource.getMessage(
                "command.current.tasks.header",
                arrayOf<Any>(tasks.size),
                locale,
            )
            val lines = tasks.joinToString(separator = "\n") { task ->
                val mark = if (task.status == TaskStatus.DONE) "✅" else "▫️"
                val title = escapeHtml(task.title)
                val styled = if (task.status == TaskStatus.DONE) "<s>$title</s>" else title
                "$mark $styled"
            }
            "<b>${escapeHtml(header)}</b>\n$lines"
        }

        val header = "<b>${escapeHtml(statusLabel)}</b>"
        val body = listOf(header, summaryBlock, taskBlock).joinToString(separator = "\n\n")

        context.channel.send(ChannelMessage.Text(body))
    }

    private fun userLocale(context: BotCommandContext): Locale {
        val lang = userSettingsService.getOrCreate(context.userId).preferredLanguage
        return Locale.forLanguageTag(lang)
    }

    private fun escapeHtml(input: String): String = input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
