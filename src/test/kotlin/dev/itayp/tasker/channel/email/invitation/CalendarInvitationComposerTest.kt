package dev.itayp.tasker.channel.email.invitation

import dev.itayp.tasker.channel.email.EmailTemplateEngine
import org.springframework.context.support.StaticMessageSource
import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.channel.email.ICalAttachment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.time.ZoneOffset
import java.time.ZonedDateTime

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension::class)
class CalendarInvitationComposerTest {

    private val outboundChannel: OutboundChannel = mock()
    private val messageSource = StaticMessageSource().apply {
        addMessage("email.invitation.when", java.util.Locale.ENGLISH, "When")
        addMessage("email.invitation.location", java.util.Locale.ENGLISH, "Location")
        addMessage("email.invitation.description", java.util.Locale.ENGLISH, "Description")
        addMessage("email.invitation.footer", java.util.Locale.ENGLISH, "Footer")
    }
    private val emailTemplateEngine = EmailTemplateEngine(messageSource)
    private val composer = CalendarInvitationComposer(outboundChannel, emailTemplateEngine)

    private val fixedStart = ZonedDateTime.of(2026, 5, 7, 9, 0, 0, 0, ZoneOffset.UTC)
    private val fixedEnd = ZonedDateTime.of(2026, 5, 7, 10, 0, 0, 0, ZoneOffset.UTC)

    private fun sampleEvent(
        uid: String = "test-uid-123",
        title: String = "Team Sync",
        description: String? = "Weekly check-in",
        location: String? = "Room 1",
    ) = CalendarEvent(
        uid = uid,
        title = title,
        description = description,
        start = fixedStart,
        end = fixedEnd,
        location = location,
        organizerEmail = "organizer@example.com",
        organizerName = "Alice",
        attendeeEmails = listOf("bob@example.com", "carol@example.com"),
    )

    @Test
    fun `sendInvitation sends EmailMessage with iCal attachment to outboundChannel`() {
        val event = sampleEvent()
        composer.sendInvitation(listOf("bob@example.com"), event)

        val captor = argumentCaptor<EmailMessage>()
        verify(outboundChannel).send(captor.capture())
        val msg = captor.firstValue

        assertEquals(listOf("bob@example.com"), msg.to)
        assertEquals("Team Sync", msg.subject)
        assertNotNull(msg.iCalAttachment)
        assertEquals("REQUEST", msg.iCalAttachment!!.method)
    }

    @Test
    fun `buildICalContent contains required RFC-5545 fields`() {
        val event = sampleEvent()
        val ical = composer.buildICalContent(event)

        assertTrue(ical.contains("BEGIN:VCALENDAR"), "missing VCALENDAR")
        assertTrue(ical.contains("METHOD:REQUEST"), "missing METHOD")
        assertTrue(ical.contains("BEGIN:VEVENT"), "missing VEVENT")
        assertTrue(ical.contains("UID:test-uid-123"), "missing UID")
        assertTrue(ical.contains("DTSTART:20260507T090000Z"), "missing DTSTART")
        assertTrue(ical.contains("DTEND:20260507T100000Z"), "missing DTEND")
        assertTrue(ical.contains("SUMMARY:Team Sync"), "missing SUMMARY")
        assertTrue(ical.contains("DESCRIPTION:Weekly check-in"), "missing DESCRIPTION")
        assertTrue(ical.contains("LOCATION:Room 1"), "missing LOCATION")
        assertTrue(ical.contains("ORGANIZER;CN=Alice:mailto:organizer@example.com"), "missing ORGANIZER")
        assertTrue(ical.contains("mailto:bob@example.com"), "missing attendee bob")
        assertTrue(ical.contains("mailto:carol@example.com"), "missing attendee carol")
        assertTrue(ical.contains("PARTSTAT=NEEDS-ACTION"), "missing PARTSTAT")
        assertTrue(ical.contains("RSVP=FALSE"), "missing RSVP")
        assertTrue(ical.contains("END:VEVENT"), "missing END:VEVENT")
        assertTrue(ical.contains("END:VCALENDAR"), "missing END:VCALENDAR")
    }

    @Test
    fun `buildICalContent includes a 15-minute display reminder`() {
        val ical = composer.buildICalContent(sampleEvent())

        assertTrue(ical.contains("BEGIN:VALARM"), "missing VALARM")
        assertTrue(ical.contains("ACTION:DISPLAY"), "missing alarm ACTION")
        assertTrue(ical.contains("TRIGGER:-PT15M"), "missing 15-minute TRIGGER")
        assertTrue(ical.contains("END:VALARM"), "missing END:VALARM")
    }

    @Test
    fun `cancellation content has no reminder`() {
        val ical = composer.buildCancellationICalContent(sampleEvent())

        assertTrue(ical.contains("METHOD:CANCEL"), "missing CANCEL method")
        assertFalse(ical.contains("VALARM"), "cancellation should not carry a reminder")
    }

    @Test
    fun `buildICalContent uses CRLF line endings`() {
        val ical = composer.buildICalContent(sampleEvent())
        assertTrue(ical.contains("\r\n"), "iCal must use CRLF line endings")
    }

    @Test
    fun `buildICalContent folds lines longer than 75 characters`() {
        val longTitle = "A".repeat(100)
        val event = sampleEvent(title = longTitle)
        val ical = composer.buildICalContent(event)
        val lines = ical.split("\r\n")
        lines.forEach { line ->
            assertTrue(line.length <= 75, "Line too long (${line.length}): '$line'")
        }
    }

    @Test
    fun `buildICalContent escapes special characters in text fields`() {
        val event = sampleEvent(
            title = "Meeting; with, commas\\and\\backslashes",
            description = "Line1\nLine2",
        )
        val ical = composer.buildICalContent(event)
        assertTrue(ical.contains("SUMMARY:Meeting\\; with\\, commas\\\\and\\\\backslashes"))
        assertTrue(ical.contains("DESCRIPTION:Line1\\nLine2"))
    }

    @Test
    fun `buildHtmlBody includes event title and date range`() {
        val html = composer.buildHtmlBody(sampleEvent())
        assertTrue(html.contains("Team Sync"))
        assertTrue(html.contains("When"))
        assertTrue(html.contains("2026"))
    }

    @Test
    fun `buildICalContent omits optional fields when absent`() {
        val event = sampleEvent(description = null, location = null)
        val ical = composer.buildICalContent(event)
        assertTrue(!ical.contains("DESCRIPTION:"), "should not include DESCRIPTION when null")
        assertTrue(!ical.contains("LOCATION:"), "should not include LOCATION when null")
    }

    @Test
    fun `buildICalContent handles organizer without name`() {
        val event = sampleEvent().copy(organizerName = null)
        val ical = composer.buildICalContent(event)
        assertTrue(ical.contains("ORGANIZER:mailto:organizer@example.com"))
    }
}
