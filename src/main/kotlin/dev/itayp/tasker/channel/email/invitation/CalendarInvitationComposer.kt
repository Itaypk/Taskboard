package dev.itayp.tasker.channel.email.invitation

import dev.itayp.tasker.ai.prompt.PromptTemplateLoader
import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.channel.email.ICalAttachment
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Component
class CalendarInvitationComposer(
    private val outboundChannel: OutboundChannel,
    private val templateLoader: PromptTemplateLoader,
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
                add(fold("ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:$email"))
            }
            add("SEQUENCE:0")
            add("STATUS:CONFIRMED")
            add("END:VEVENT")
            add("END:VCALENDAR")
        }
        return lines.joinToString("\r\n") + "\r\n"
    }

    fun buildHtmlBody(event: CalendarEvent): String {
        val template = templateLoader.loadFromClasspath("emails/invitation.html")
        return template.render(
            mapOf(
                "title" to htmlEscape(event.title),
                "date_range" to "${formatDisplay(event.start)} – ${formatDisplay(event.end)}",
                "location_section" to (event.location?.let {
                    "<p><strong>Location:</strong> ${htmlEscape(it)}</p>"
                } ?: ""),
                "description_section" to (event.description?.let {
                    "<p><strong>Description:</strong> ${htmlEscape(it)}</p>"
                } ?: ""),
            ),
        )
    }

    private fun formatUtc(dt: ZonedDateTime): String =
        dt.withZoneSameInstant(ZoneOffset.UTC).format(ICAL_UTC_FORMAT)

    private fun formatDisplay(dt: ZonedDateTime): String =
        dt.format(DateTimeFormatter.ofPattern("EEE, MMM d yyyy HH:mm z"))

    // RFC 5545 §3.3.11 — escape backslash, semicolon, comma, newlines in text values
    private fun escapeText(s: String): String = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")
        .replace("\r", "")

    // Minimal escape for CN= parameter values (avoid ; and ")
    private fun escapeParam(s: String): String = s.replace("\"", "'").replace(";", " ")

    private fun htmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // RFC 5545 §3.1 — fold lines longer than 75 octets at whitespace
    private fun fold(line: String): String {
        if (line.length <= 75) return line
        val sb = StringBuilder()
        var offset = 0
        var firstChunk = true
        while (offset < line.length) {
            val limit = if (firstChunk) 75 else 74
            val end = minOf(offset + limit, line.length)
            if (!firstChunk) sb.append("\r\n ")
            sb.append(line, offset, end)
            offset = end
            firstChunk = false
        }
        return sb.toString()
    }

    companion object {
        private val ICAL_UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    }
}
