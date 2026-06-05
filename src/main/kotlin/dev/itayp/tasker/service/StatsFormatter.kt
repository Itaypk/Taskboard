package dev.itayp.tasker.service

import dev.itayp.tasker.model.UserStats
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import java.text.NumberFormat
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Turns a [UserStats] snapshot into channel-agnostic, fully-localized lines of plain text.
 * Numbers and the join date are formatted here once so every surface (Telegram now, web later)
 * shares the same wording; each channel only decides how to present the lines (e.g. bolding the
 * header, joining, escaping). The first line is always the header.
 */
@Component
class StatsFormatter(private val messageSource: MessageSource) {

    fun format(stats: UserStats, locale: Locale, zone: ZoneId): List<String> {
        if (stats.isEmpty) {
            return listOf(msg("command.stats.header", locale), msg("command.stats.empty", locale))
        }

        val numberFormat = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }

        return buildList {
            add(msg("command.stats.header", locale))
            stats.joinedAt?.let { joined ->
                val joinedDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(locale)
                    .format(joined.atZone(zone))
                add(msg("command.stats.joined", locale, joinedDate))
            }
            add(msg("command.stats.openTasks", locale, stats.openTasks))
            add(msg("command.stats.completedTasks", locale, stats.completedTasks))
            add(msg(
                "command.stats.perWeek",
                locale,
                numberFormat.format(stats.avgTasksCreatedPerWeek),
                numberFormat.format(stats.avgTasksCompletedPerWeek),
            ))
            add(
                stats.avgCompletion
                    ?.let { msg("command.stats.avgCompletion", locale, humanizeDuration(it, locale, numberFormat)) }
                    ?: msg("command.stats.avgCompletion.none", locale)
            )
            add(msg("command.stats.sessions", locale, stats.planningSessions))
        }
    }

    private fun humanizeDuration(duration: Duration, locale: Locale, numberFormat: NumberFormat): String {
        val hours = duration.toMinutes() / 60.0
        return if (hours >= 24.0) {
            msg("command.stats.unit.days", locale, numberFormat.format(hours / 24.0))
        } else {
            msg("command.stats.unit.hours", locale, numberFormat.format(hours))
        }
    }

    private fun msg(key: String, locale: Locale, vararg args: Any): String =
        messageSource.getMessage(key, args, locale)
}
