package dev.itayp.tasker.calendar.source

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CalendarUrlsTest {

    @Test
    fun `keeps https and trims`() {
        assertEquals(
            "https://calendar.google.com/calendar/ical/x/private-abc/basic.ics",
            CalendarUrls.normalize("  https://calendar.google.com/calendar/ical/x/private-abc/basic.ics\n").toString(),
        )
    }

    @Test
    fun `maps webcal to https`() {
        assertEquals("https://p01-caldav.icloud.com/published/2/abc", CalendarUrls.normalize("webcal://p01-caldav.icloud.com/published/2/abc").toString())
        assertEquals("https://example.com/a.ics", CalendarUrls.normalize("WEBCALS://example.com/a.ics").toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "http://example.com/a.ics", "ftp://example.com/a.ics", "file:///etc/passwd", "example.com/a.ics", "https://user:pw@example.com/a.ics", "https:///a.ics", "https://exa mple.com/a.ics"])
    fun `refuses anything that isn't https`(raw: String) {
        val e = assertFailsWith<IcsFetchException> { CalendarUrls.normalize(raw) }
        assertEquals(IcsFetchError.NOT_HTTPS, e.error)
    }
}
