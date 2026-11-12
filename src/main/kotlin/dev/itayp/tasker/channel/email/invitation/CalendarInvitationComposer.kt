package dev.itayp.tasker.channel.email.invitation

import dev.itayp.tasker.calendar.ICalWriter
import dev.itayp.tasker.calendar.ICalWriter.escapeParam
import dev.itayp.tasker.calendar.ICalWriter.escapeText
import dev.itayp.tasker.calendar.ICalWriter.fold
import dev.itayp.tasker.calendar.ICalWriter.formatUtc
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.channel.email.ICalAttachment
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

@Component
class CalendarInvitationComposer(
    @Qualifier("schedulingEmailChannel") private val outboundChannel: OutboundChannel,
    private val emailTemplateEngine: EmailTemplateEngine,
) {

    fun sendInvitation(to: List<String>, event: CalendarEvent) {
        val iCalContent = buildICalContent(event)
        val htmlBody = buildHtmlBody(event)
        outboundChannel.send(
            EmailMessage(
                to = to,
                subject = event.title,
                htmlBody = htmlBody,
                iCalAttachment = ICalAttachment(method = "REQUEST", content = iCalContent),
            ),
        )
    }

    fun sendCancellation(to: List<String>, event: CalendarEvent) {
        val iCalContent = buildCancellationICalContent(event)
        val htmlBody = buildCancellationHtmlBody(event)
        outboundChannel.send(
            EmailMessage(
                to = to,
                subject = event.title,
                htmlBody = htmlBody,
                iCalAttachment = ICalAttachment(method = "CANCEL", content = iCalContent),
            ),
        )
    }

    fun buildCancellationICalContent(event: CalendarEvent): String {
        val dtstamp = formatUtc(ZonedDateTime.now(ZoneOffset.UTC))
        val dtstart = formatUtc(event.start)
        val dtend = formatUtc(event.end)

        val lines = buildList {
            add("BEGIN:VCALENDAR")
            add("VERSION:2.0")
            add("PRODID:-//Backlog.fyi//EN")
            add("CALSCALE:GREGORIAN")
            add("METHOD:CANCEL")
            add("BEGIN:VEVENT")
            add("UID:${event.uid}")
            add("DTSTAMP:$dtstamp")
            add("DTSTART:$dtstart")
            add("DTEND:$dtend")
            add(fold("SUMMARY:${escapeText(event.title)}"))
            val organizerCn = event.organizerName?.let { ";CN=${escapeParam(it)}" } ?: ""
            add(fold("ORGANIZER${organizerCn}:mailto:${event.organizerEmail}"))
            for (email in event.attendeeEmails) {
                add(fold("ATTENDEE;PARTSTAT=DECLINED:mailto:$email"))
            }
            add("SEQUENCE:${event.sequence}")
            add("STATUS:CANCELLED")
            add("END:VEVENT")
            add("END:VCALENDAR")
        }
        return ICalWriter.join(lines)
    }

    fun buildCancellationHtmlBody(event: CalendarEvent): String {
        return emailTemplateEngine.render(
            "emails/cancellation.html",
            mapOf(
                "title" to event.title,
                "date_range" to "${formatDisplay(event.start, event.locale)} – ${formatDisplay(event.end, event.locale)}",
            ),
            event.locale
        )
    }

    fun buildICalContent(event: CalendarEvent): String {
        val dtstamp = formatUtc(ZonedDateTime.now(ZoneOffset.UTC))
        val dtstart = formatUtc(event.start)
        val dtend = formatUtc(event.end)

        val lines = buildList {
            add("BEGIN:VCALENDAR")
            add("VERSION:2.0")
            add("PRODID:-//Backlog.fyi//EN")
            add("CALSCALE:GREGORIAN")
            add("METHOD:REQUEST")
            add("BEGIN:VEVENT")
            add("UID:${event.uid}")
            add("DTSTAMP:$dtstamp")
            add("DTSTART:$dtstart")
            add("DTEND:$dtend")
            add(fold("SUMMARY:${escapeText(event.title)}"))
            event.description?.let { add(fold("DESCRIPTION:${escapeText(it)}")) }
            event.location?.let { add(fold("LOCATION:${escapeText(it)}")) }
            val organizerCn = event.organizerName?.let { ";CN=${escapeParam(it)}" } ?: ""
            add(fold("ORGANIZER${organizerCn}:mailto:${event.organizerEmail}"))
            for (email in event.attendeeEmails) {
                val rsvp = if (event.rsvp) "TRUE" else "FALSE"
                add(fold("ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=$rsvp:mailto:$email"))
            }
            add("SEQUENCE:${event.sequence}")
            add("STATUS:CONFIRMED")
            // 15-minute pop-up reminder so accepted blocks actually notify the user.
            add("BEGIN:VALARM")
            add("ACTION:DISPLAY")
            add(fold("DESCRIPTION:${escapeText(event.title)}"))
            add("TRIGGER:-PT15M")
            add("END:VALARM")
            add("END:VEVENT")
            add("END:VCALENDAR")
        }
        return ICalWriter.join(lines)
    }

    fun buildHtmlBody(event: CalendarEvent): String {
        return emailTemplateEngine.render(
            "emails/invitation.html",
            mapOf(
                "title" to event.title,
                "date_range" to "${formatDisplay(event.start, event.locale)} – ${formatDisplay(event.end, event.locale)}",
                "location" to event.location,
                "description" to event.description,
            ),
            event.locale
        )
    }

    /** The locale's own date and time order and clock (`28 Oct 2026, 14:00` for en-GB, `Oct 28, 2026, 2:00 PM` for en-US). */
    private fun formatDisplay(dt: ZonedDateTime, locale: Locale): String =
        dt.format(DISPLAY_FORMAT.withLocale(locale))

    companion object {
        private val DISPLAY_FORMAT: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendLocalized("yMMMEdjm")
            .appendLiteral(' ')
            .appendPattern("z")
            .toFormatter()
    }
}
