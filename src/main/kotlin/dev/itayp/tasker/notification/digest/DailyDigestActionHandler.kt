package dev.itayp.tasker.notification.digest

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.planning.PlanningSessionService
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/**
 * Handles a tap on a daily digest's buttons. The channel layer hands over every callback; anything
 * without the `dig:` prefix comes back as [Result.NotOurs]. The digest row is reloaded from the DB,
 * so a mute applies to exactly the due tasks — at exactly the deadlines — the user was shown.
 *
 * "Revisit the plan" isn't carried out here: planning conversations are bound to a Telegram chat, so
 * the handler answers [Result.StartPlanning] and the channel runs its `/plan` flow.
 */
@Component
class DailyDigestActionHandler(
    private val repository: DailyDigestRepository,
    private val muteService: DeadlineReminderMuteService,
    private val planningSessionService: PlanningSessionService,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(DailyDigestActionHandler::class.java)

    sealed interface Result {
        /** Not a digest callback; the caller keeps routing it. */
        data object NotOurs : Result

        /** Handled, and the user has been answered. */
        data object Handled : Result

        /** The user asked to revisit the plan; the caller should run its `/plan` flow. */
        data object StartPlanning : Result
    }

    @Transactional
    fun handle(userId: UUID, channel: ConversationChannel, callbackData: String): Result {
        val parsed = DailyDigestAction.parse(callbackData)
        if (parsed == null) {
            if (callbackData.startsWith(DailyDigestAction.PREFIX)) {
                // Looks like one of ours but didn't decode — a malformed payload is a bug, not routing.
                log.error("Discarding unparseable digest callback data: '{}'", callbackData)
            }
            return Result.NotOurs
        }
        val locale = userSettingsService.getLocale(userId)
        val digest = repository.findById(parsed.digestId).orElse(null)
        // A stale button (account reset) or a payload not owned by this user.
        if (digest == null || digest.userId != userId) {
            channel.send(ChannelMessage.Text(msg("digest.action.expired", locale)))
            count(parsed.action, "stale", channel)
            return Result.Handled
        }

        val result = when (parsed.action) {
            DailyDigestAction.ACK -> {
                channel.send(ChannelMessage.Text(msg("digest.ack.confirmed", locale)))
                Result.Handled
            }
            DailyDigestAction.REVISIT_PLAN -> Result.StartPlanning
            DailyDigestAction.MUTE_DUE_UNTIL_NEXT_WEEK -> {
                // The same week boundary `/plan` uses, whether or not this week has a plan.
                val nextWeekStart = planningSessionService.currentWeekStart(userId).plusWeeks(1)
                muteService.mute(userId, digest.dueTasks, until = nextWeekStart)
                val date = nextWeekStart.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                channel.send(ChannelMessage.Text(msg("digest.mute.next_week.confirmed", locale, date)))
                Result.Handled
            }
            DailyDigestAction.MUTE_DUE_FOR_GOOD -> {
                muteService.mute(userId, digest.dueTasks, until = today(userId).plusYears(1))
                channel.send(ChannelMessage.Text(msg("digest.mute.for_good.confirmed", locale)))
                Result.Handled
            }
        }
        count(parsed.action, "ok", channel)
        log.debug("Handled digest action {} for digest {}", parsed.action, digest.id)
        return result
    }

    private fun today(userId: UUID): LocalDate {
        val zone = runCatching { ZoneId.of(userSettingsService.getOrCreate(userId).timeZone) }
            .getOrDefault(ZoneId.of("UTC"))
        return LocalDate.ofInstant(clock.instant(), zone)
    }

    private fun msg(key: String, locale: Locale, vararg args: Any): String =
        messageSource.getMessage(key, args.takeIf { it.isNotEmpty() }, locale)

    private fun count(action: DailyDigestAction, outcome: String, channel: ConversationChannel) {
        meterRegistry.counter(
            "tasker.notification.action",
            "type", "daily_digest",
            "channel", channel.type.metricTag,
            "action", action.code,
            "outcome", outcome,
        ).increment()
    }
}
