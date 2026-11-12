package dev.itayp.tasker.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ICalWriterTest {

    private fun unfold(folded: String) = folded.replace("\r\n ", "")

    private fun octets(line: String) = line.toByteArray(Charsets.UTF_8).size

    @Test
    fun `short lines are left alone`() {
        assertEquals("SUMMARY:Team sync", ICalWriter.fold("SUMMARY:Team sync"))
    }

    @Test
    fun `ASCII lines fold at 75 octets and unfold to the original`() {
        val line = "SUMMARY:" + "A".repeat(200)
        val folded = ICalWriter.fold(line)
        folded.split("\r\n").forEach { assertTrue(octets(it) <= 75, "too long: ${octets(it)}") }
        assertEquals(line, unfold(folded))
    }

    @Test
    fun `multi-byte text is folded by octets, not chars`() {
        val line = "SUMMARY:" + "שלום עולם ".repeat(10)
        val folded = ICalWriter.fold(line)
        folded.split("\r\n").forEach { assertTrue(octets(it) <= 75, "too long: ${octets(it)}") }
        assertEquals(line, unfold(folded))
    }

    @Test
    fun `an emoji is never split across lines`() {
        // Offset the emoji so that a char-count fold would land in the middle of a surrogate pair.
        val line = "SUMMARY:" + "x".repeat(66) + "🎉".repeat(20)
        val folded = ICalWriter.fold(line)
        folded.split("\r\n").forEach { part ->
            assertTrue(octets(part) <= 75, "too long: ${octets(part)}")
            // A lone surrogate doesn't survive a UTF-8 round trip.
            assertEquals(part, String(part.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
        assertEquals(line, unfold(folded))
    }

    @Test
    fun `escapeText escapes RFC 5545 specials`() {
        assertEquals("a\\, b\\; c\\\\ d\\ne", ICalWriter.escapeText("a, b; c\\ d\r\ne"))
    }

    @Test
    fun `formatUtc converts to UTC`() {
        val dt = ZonedDateTime.of(2026, 5, 7, 12, 0, 0, 0, ZoneId.of("Asia/Jerusalem"))
        assertEquals("20260507T090000Z", ICalWriter.formatUtc(dt))
    }

    @Test
    fun `join ends every line with CRLF`() {
        assertEquals("A\r\nB\r\n", ICalWriter.join(listOf("A", "B")))
    }
}
