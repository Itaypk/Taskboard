package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.model.UserStats
import dev.itayp.tasker.service.StatsService
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.MessageSource
import org.springframework.stereotype.Component
import org.springframework.web.util.HtmlUtils
import java.text.NumberFormat
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Renders a fun, on-demand snapshot of the user's activity over Telegram. Purely informational —
 * see [StatsService] / [UserStats]. Numbers are localized; the join date is rendered in the user's
 * own time zone.
 */
@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class StatsBotCommand(
    private val statsService: StatsService,
    private val userSettingsService: UserSettingsService,
    private val messageSource: MessageSource,
) : BotCommandHandler {

    override val command = "stats"

    override val description = "Show your activity stats"

    override fun handle(context: BotCommandContext) {
        val settings = userSettingsService.getOrCreate(context.userId)
        val locale = Locale.forLanguageTag(settings.preferredLanguage)
        val stats = statsService.computeStats(context.userId)

        val header = "<b>${HtmlUtils.htmlEscape(msg("command.stats.header", locale))}</b>"

        if (stats.isEmpty) {
            context.channel.send(ChannelMessage.Text("$header\n\n${HtmlUtils.htmlEscape(msg("command.stats.empty", locale))}"))
            return
        }

        val numberFormat = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }

        val lines = buildList {
            stats.joinedAt?.let { joined ->
                val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
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
            val completion = stats.avgCompletion
                ?.let { msg("command.stats.avgCompletion", locale, humanizeDuration(it, locale, numberFormat)) }
                ?: msg("command.stats.avgCompletion.none", locale)
            add(completion)
            add(msg("command.stats.sessions", locale, stats.planningSessions))
        }

        val body = "$header\n\n" + lines.joinToString(separator = "\n") { HtmlUtils.htmlEscape(it) }
        context.channel.send(ChannelMessage.Text(body))
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
