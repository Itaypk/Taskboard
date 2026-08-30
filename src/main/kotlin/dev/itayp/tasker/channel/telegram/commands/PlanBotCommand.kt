package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.Companion.OPTION_DISMISS
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.Companion.OPTION_KEEP
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.Companion.OPTION_NEXT_WEEK
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.Companion.OPTION_REVISE
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.Companion.OPTION_THIS_WEEK
import dev.itayp.tasker.channel.telegram.commands.PlanConfirmationRegistry.PendingConfirmation
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.planning.WeekOffset
import dev.itayp.tasker.planning.WeekResolver
import dev.itayp.tasker.planning.WeeklyPlanningOrchestrator
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class PlanBotCommand(
    private val orchestrator: WeeklyPlanningOrchestrator,
    private val planningSessionService: PlanningSessionService,
    private val planConfirmationRegistry: PlanConfirmationRegistry,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
    private val clock: Clock,
) : BotCommandHandler {

    override val command = "plan"

    override val description = "Start or review your weekly planning session"

    override val requiresAi = true

    override fun handle(context: BotCommandContext) {
        val locale = userSettingsService.getLocale(context.userId)
        val existingSessionId = context.sessionRegistry.get(context.chatId)

        // Case 1: active session still alive in memory
        if (existingSessionId != null && orchestrator.phase(existingSessionId) != null) {
            val activeSession = planningSessionService.findById(context.userId, existingSessionId)
            planConfirmationRegistry.set(
                context.chatId,
                PendingConfirmation(
                    userId = context.userId,
                    existingSessionId = existingSessionId,
                    replanWeekStart = activeSession?.weekStart,
                ),
            )
            context.channel.send(ChannelMessage.Choice(
                prompt = prompt(context, locale, "planning.confirm.active.prompt"),
                options = options(
                    context, locale,
                    ChoiceOption(OPTION_KEEP, messageSource.getMessage("planning.confirm.active.continue", null, locale)),
                    ChoiceOption(OPTION_THIS_WEEK, messageSource.getMessage("planning.confirm.active.abandon", null, locale)),
                ),
            ))
            return
        }

        // Case 2: completed plan exists in DB — offer keep / revise / start-over for the same week
        val existingPlan = planningSessionService.findCurrentPlan(context.userId)
        if (existingPlan?.status == PlanningSessionStatus.COMPLETED && existingPlan.summary != null) {
            planConfirmationRegistry.set(
                context.chatId,
                PendingConfirmation(
                    userId = context.userId,
                    existingSessionId = null,
                    replanWeekStart = existingPlan.weekStart,
                    revisableSessionId = existingPlan.id,
                    // Only revision can use it: it opens straight into a conversation, where a
                    // fresh session opens on a capacity question (`docs/FREE-TEXT-CAPTURE.md` D3a).
                    revisionSeed = context.inferredFrom?.takeIf { it.isNotBlank() },
                ),
            )
            context.channel.send(ChannelMessage.Choice(
                prompt = prompt(context, locale, "planning.confirm.completed.prompt", existingPlan.summary),
                options = options(
                    context, locale,
                    ChoiceOption(OPTION_KEEP, messageSource.getMessage("planning.confirm.completed.keep", null, locale)),
                    ChoiceOption(OPTION_REVISE, messageSource.getMessage("planning.confirm.completed.revise", null, locale)),
                    ChoiceOption(OPTION_THIS_WEEK, messageSource.getMessage("planning.confirm.completed.start_over", null, locale)),
                ),
            ))
            return
        }

        // Case 3: no existing plan — ask which week to plan for
        planConfirmationRegistry.set(
            context.chatId,
            PendingConfirmation(
                userId = context.userId,
                existingSessionId = null,
                replanWeekStart = null,
            ),
        )
        val settings = userSettingsService.getOrCreate(context.userId)
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
        val today = LocalDate.now(clock.withZone(zone))
        val weekStartDay = WeekResolver.parseWeekStartDay(settings.weekStartDay)
        val thisWeek = WeekResolver.resolveWeekStart(today, weekStartDay, WeekOffset.CURRENT)
        val nextWeek = WeekResolver.resolveWeekStart(today, weekStartDay, WeekOffset.NEXT)
        val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val thisLabel = messageSource.getMessage(
            "planning.choose_week.this_week",
            arrayOf<Any>(thisWeek.format(fmt), thisWeek.plusDays(6).format(fmt)),
            locale,
        )
        val nextLabel = messageSource.getMessage(
            "planning.choose_week.next_week",
            arrayOf<Any>(nextWeek.format(fmt), nextWeek.plusDays(6).format(fmt)),
            locale,
        )
        context.channel.send(ChannelMessage.Choice(
            prompt = prompt(context, locale, "planning.choose_week.prompt"),
            options = options(
                context, locale,
                ChoiceOption(OPTION_THIS_WEEK, thisLabel),
                ChoiceOption(OPTION_NEXT_WEEK, nextLabel),
            ),
        ))
    }

    /**
     * Leads with what was inferred when the user never typed `/plan`, so a wrong guess is obvious
     * before they answer the question underneath it.
     */
    private fun prompt(context: BotCommandContext, locale: Locale, key: String, vararg args: Any): String {
        val body = messageSource.getMessage(key, args.takeIf { it.isNotEmpty() }, locale)
        if (!context.inferred) return body
        return messageSource.getMessage("planning.inferred.ack", null, locale) + "\n\n" + body
    }

    /**
     * `/plan` was typed on purpose, so its own options are answer enough; an *inferred* intent can
     * simply be wrong, and every state needs a way out of a conversation the user didn't ask for.
     */
    private fun options(context: BotCommandContext, locale: Locale, vararg base: ChoiceOption): List<ChoiceOption> {
        if (!context.inferred) return base.toList()
        return base.toList() + ChoiceOption(
            OPTION_DISMISS,
            messageSource.getMessage("planning.confirm.dismiss", null, locale),
        )
    }
}
