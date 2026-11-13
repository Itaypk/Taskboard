package dev.itayp.tasker.calendar

import dev.itayp.tasker.calendar.ICalWriter.escapeText
import dev.itayp.tasker.calendar.ICalWriter.fold
import dev.itayp.tasker.calendar.ICalWriter.formatUtc
import dev.itayp.tasker.config.AppProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Renders [CalendarContent] as the body of a subscription feed (`METHOD:PUBLISH`, RFC 5545).
 *
 * The output is deterministic for the same content and [stamp], so its hash works as an ETag.
 *
 * UIDs differ from the ones emailed invitations use (`<taskId>-<startIso>`): a user switching
 * from invitations to the feed has the invitations cancelled, and a cancelled invitation and a feed
 * event must never be mistaken for the same event. They end in `@<our host>`, so a calendar we read
 * back (GitHub issue #347) can recognise and skip our own events.
 *
 * No `VALARM`: Google ignores alarms in subscribed calendars and Apple strips them by default, so
 * they would only fire for some users; Telegram reminders are the reminder channel.
 */
@Component
class CalendarFeedRenderer(private val appProperties: AppProperties) {

    private val host: String = runCatching { URI(appProperties.baseUrl).host }.getOrNull() ?: "localhost"

    /**
     * [stamp] goes into every event's `DTSTAMP` and `LAST-MODIFIED`: the time the plan last changed,
     * so a client that keys updates on them sees a change exactly when there is one.
     */
    fun render(content: CalendarContent, stamp: Instant): String {
        val dtstamp = formatUtc(stamp.atZone(ZoneOffset.UTC))
        val lines = buildList {
            add("BEGIN:VCALENDAR")
            add("VERSION:2.0")
            add(fold("PRODID:-//${escapeText(appProperties.name)}//Calendar feed//EN"))
            add("CALSCALE:GREGORIAN")
            add("METHOD:PUBLISH")
            add(fold("X-WR-CALNAME:${escapeText(appProperties.name)}"))
            // Hints only: Google and Outlook poll on their own schedule; Apple and some others honour these.
            add("REFRESH-INTERVAL;VALUE=DURATION:$REFRESH")
            add("X-PUBLISHED-TTL:$REFRESH")

            for (task in content.planTasks) {
                for (slot in task.slots) {
                    val start = OffsetDateTime.parse(slot.startIso).toZonedDateTime()
                    val end = OffsetDateTime.parse(slot.endIso).toZonedDateTime()
                    addEvent(
                        uid = "plan-${task.taskId}-${start.toEpochSecond()}@$host",
                        dtstamp = dtstamp,
                        start = formatUtc(start),
                        end = formatUtc(end),
                        title = task.title,
                        description = task.notes,
                        location = null,
                    )
                }
            }
            for (event in content.oneOffEvents) {
                addEvent(
                    uid = "event-${event.id}@$host",
                    dtstamp = dtstamp,
                    start = formatUtc(event.startsAt.atZone(ZoneOffset.UTC)),
                    end = formatUtc(event.endsAt.atZone(ZoneOffset.UTC)),
                    title = event.title,
                    description = event.notes,
                    location = event.location,
                )
            }
            add("END:VCALENDAR")
        }
        return ICalWriter.join(lines)
    }

    private fun MutableList<String>.addEvent(
        uid: String,
        dtstamp: String,
        start: String,
        end: String,
        title: String,
        description: String?,
        location: String?,
    ) {
        add("BEGIN:VEVENT")
        add(fold("UID:$uid"))
        add("DTSTAMP:$dtstamp")
        add("LAST-MODIFIED:$dtstamp")
        add("DTSTART:$start")
        add("DTEND:$end")
        add(fold("SUMMARY:${escapeText(title)}"))
        description?.takeIf { it.isNotBlank() }?.let { add(fold("DESCRIPTION:${escapeText(it)}")) }
        location?.takeIf { it.isNotBlank() }?.let { add(fold("LOCATION:${escapeText(it)}")) }
        add("STATUS:CONFIRMED")
        // Planned blocks are time the user set aside, so they show as busy.
        add("TRANSP:OPAQUE")
        add("END:VEVENT")
    }

    companion object {
        const val REFRESH = "PT1H"
    }
}
