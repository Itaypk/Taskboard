package dev.itayp.tasker.planning

import dev.itayp.tasker.oneoff.OneOffEventService
import dev.itayp.tasker.service.UserSettingsService
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Returns a human-readable description of the user's calendar across the planning window.
 * Real implementations will pull from Google Calendar; for now we surface one-off events the
 * user captured through /add (with a "partial" caveat) so the assistant doesn't propose time
 * blocks that collide with them.
 */
interface CalendarWindowProvider {
    fun describeWindow(userId: UUID, from: Instant, to: Instant): String
}

/**
 * Surfaces one-off events captured through `/add` as the user's known calendar. Until Google
 * Calendar is integrated this is only ever a partial view of their real schedule — the
 * [PARTIAL_NOTICE] is emitted whenever events exist so the assistant treats the list as
 * incomplete and doesn't confidently slot work into an already-booked time.
 */
@Component
class OneOffEventCalendarWindowProvider(
    private val oneOffEventService: OneOffEventService,
    private val userSettingsService: UserSettingsService,
) : CalendarWindowProvider {

    override fun describeWindow(userId: UUID, from: Instant, to: Instant): String {
        val events = oneOffEventService.listForWeek(userId, from, to)
        if (events.isEmpty()) return EMPTY_PLACEHOLDER

        val settings = userSettingsService.getOrCreate(userId)
        val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
        val locale = runCatching { Locale.forLanguageTag(settings.preferredLanguage) }.getOrDefault(Locale.ENGLISH)
        val formatter = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm", locale)

        val lines = events.joinToString("\n") { event ->
            val start = event.startsAt.atZone(zone)
            val end = event.endsAt.atZone(zone).toLocalTime()
            val locationSuffix = event.location?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            "- ${formatter.format(start)}–$end ${zone.id} — ${event.title}$locationSuffix"
        }
        return "$PARTIAL_NOTICE\n$lines"
    }

    companion object {
        const val EMPTY_PLACEHOLDER =
            "User has not connected their calendar; assume no fixed commitments are known."
        const val PARTIAL_NOTICE =
            "Known events on the user's calendar (partial — only events captured through " +
                "Backlog.fyi; the user's other calendar entries are not visible, so don't assume " +
                "the rest of the week is free):"
    }
}
