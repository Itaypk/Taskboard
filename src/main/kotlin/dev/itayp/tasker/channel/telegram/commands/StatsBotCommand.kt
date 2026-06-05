package dev.itayp.tasker.channel.telegram.commands

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.service.StatsFormatter
import dev.itayp.tasker.service.StatsService
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.ZoneId

/**
 * Renders a fun, on-demand snapshot of the user's activity over Telegram. The wording and number
 * formatting live in [StatsFormatter] (shared with the future web surface); this handler only
 * resolves the user's locale/time zone and applies Telegram presentation — bolding the header line.
 */
@Component
@ConditionalOnProperty(prefix = "tasker.telegram", name = ["enabled"], havingValue = "true")
class StatsBotCommand(
    private val statsService: StatsService,
    private val statsFormatter: StatsFormatter,
    private val userSettingsService: UserSettingsService,
) : BotCommandHandler {

    override val command = "stats"

    override val description = "Show your activity stats"

    override fun handle(context: BotCommandContext) {
        val locale = userSettingsService.getLocale(context.userId)
        val zone = runCatching { ZoneId.of(userSettingsService.getOrCreate(context.userId).timeZone) }
            .getOrDefault(ZoneId.of("UTC"))
        val stats = statsService.computeStats(context.userId)

        val lines = statsFormatter.format(stats, locale, zone)
        val body = "<b>${lines.first()}</b>\n\n" + lines.drop(1).joinToString(separator = "\n")
        context.channel.send(ChannelMessage.Text(body))
    }
}
