package dev.itayp.tasker.notification.digest

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import dev.itayp.tasker.channel.MessageFormatter
import dev.itayp.tasker.planning.ScheduledConversationChannelResolver
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/**
 * Composes, records and sends one user's daily digest (docs/DAILY-DIGEST.md). Called by
 * [DailyDigestScheduler] once the user's digest time has come; whether it's time is the
 * scheduler's call, whether there's anything to send (and anywhere to send it) is this service's.
 *
 * Template-only by design — no AI copy. The digest row is saved before the send in the same
 * transaction, so a failed send rolls it back and leaves no record of a digest the user never got.
 */
@Service
class DailyDigestService(
    private val composer: DailyDigestComposer,
    private val repository: DailyDigestRepository,
    private val userSettingsService: UserSettingsService,
    private val channelResolver: ScheduledConversationChannelResolver,
    private val aiAccessService: AiAccessService,
    private val messageSource: MessageSource,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(DailyDigestService::class.java)

    enum class Outcome { SENT, EMPTY, NO_CHANNEL, ALREADY_SENT }

    @Transactional
    fun send(userId: UUID, now: Instant): Outcome {
        val settings = userSettingsService.getOrCreate(userId)
        val resolved = channelResolver.resolve(userId)
            ?: return skipped(userId, Outcome.NO_CHANNEL)
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
        val today = LocalDate.ofInstant(now, zone)
        // Backstop for the scheduler's watermark; the unique (user_id, digest_date) key is the last line.
        if (repository.existsByUserIdAndDigestDate(userId, today)) return skipped(userId, Outcome.ALREADY_SENT)

        val content = composer.compose(userId, today, zone, includeDue = settings.dailyDigestDueTasks)
        if (content.isEmpty) return skipped(userId, Outcome.EMPTY)

        val digest = repository.save(DailyDigestEntity().apply {
            this.userId = userId
            digestDate = today
            sentAt = now
            dueTasks = content.due.mapTo(mutableListOf()) { DigestDueTask(it.taskId, it.deadline) }
        })
        val locale = userSettingsService.toLocale(settings.preferredLanguage)
        val channel = resolved.channel
        try {
            channel.send(ChannelMessage.Choice(
                prompt = render(content, today, locale, channel.formatter),
                options = buttons(digest.id!!, userId, hasDue = content.due.isNotEmpty(), locale),
            ))
        } catch (e: Exception) {
            count("failure")
            // Rethrown so the digest row rolls back; the scheduler logs it. Not retried — the
            // watermark already moved, and the next digest is a day away.
            throw e
        }
        count("success")
        log.info(
            "Sent daily digest {} to user {} (today={}, due={}, dueOverflow={})",
            digest.id, userId, content.today.size, content.due.size, content.dueOverflow,
        )
        return Outcome.SENT
    }

    private fun render(content: DailyDigestContent, today: LocalDate, locale: Locale, formatter: MessageFormatter): String {
        val sections = mutableListOf<String>()
        sections += formatter.bold(msg("digest.header", locale, today.format(dayFormat(locale))))

        if (content.today.isNotEmpty()) {
            val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            val items = content.today.map { msg("digest.today.item", locale, it.start.format(timeFormat), it.title) }
            sections += formatter.bold(msg("digest.today.header", locale)) + "\n" + formatter.bulletList(items)
        }

        if (content.due.isNotEmpty()) {
            val dateFormat = DateTimeFormatter.ofLocalizedPattern("MMMd").withLocale(locale)
            val items = content.due.map {
                if (it.deadline == today) {
                    msg("digest.due.today", locale, it.title)
                } else {
                    msg("digest.due.overdue", locale, it.title, it.deadline.format(dateFormat))
                }
            }
            var section = formatter.bold(msg("digest.due.header", locale)) + "\n" + formatter.bulletList(items)
            if (content.dueOverflow > 0) {
                section += "\n" + formatter.escape(msg("digest.due.more", locale, content.dueOverflow))
            }
            sections += section
        }
        return sections.joinToString("\n\n")
    }

    private fun buttons(digestId: UUID, userId: UUID, hasDue: Boolean, locale: Locale): List<ChoiceOption> = buildList {
        add(option(DailyDigestAction.ACK, digestId, "digest.action.ack", locale))
        // Same gate as `/plan` (PlanBotCommand.requiresAi), so the button never leads to a refusal.
        if (aiAccessService.isAiAvailableForUser(userId)) {
            add(option(DailyDigestAction.REVISIT_PLAN, digestId, "digest.action.revisit_plan", locale))
        }
        if (hasDue) {
            add(option(DailyDigestAction.MUTE_DUE_UNTIL_NEXT_WEEK, digestId, "digest.action.mute_next_week", locale))
            add(option(DailyDigestAction.MUTE_DUE_FOR_GOOD, digestId, "digest.action.mute_for_good", locale))
        }
    }

    private fun option(action: DailyDigestAction, digestId: UUID, key: String, locale: Locale) =
        ChoiceOption(action.callbackData(digestId), msg(key, locale))

    private fun skipped(userId: UUID, outcome: Outcome): Outcome {
        count("skipped")
        log.debug("No daily digest for user {}: {}", userId, outcome)
        return outcome
    }

    private fun msg(key: String, locale: Locale, vararg args: Any): String =
        messageSource.getMessage(key, args.takeIf { it.isNotEmpty() }, locale)

    private fun count(outcome: String) {
        meterRegistry.counter(
            "tasker.notification.sent",
            "type", "daily_digest",
            "channel", "telegram",
            "outcome", outcome,
            "content", if (outcome == "skipped") "none" else "static",
        ).increment()
    }

    /** e.g. "Sat, Oct 3" — localized field order, no year. */
    private fun dayFormat(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedPattern("MMMMEEEEd").withLocale(locale)
}
