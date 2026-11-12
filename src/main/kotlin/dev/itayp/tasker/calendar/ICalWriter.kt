package dev.itayp.tasker.calendar

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * RFC 5545 content-line helpers shared by everything that writes iCalendar text: emailed
 * invitations and the subscription feed.
 */
object ICalWriter {

    private const val MAX_LINE_OCTETS = 75
    private val UTC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** A DATE-TIME in UTC form (`20260507T090000Z`). */
    fun formatUtc(dt: ZonedDateTime): String =
        dt.withZoneSameInstant(ZoneOffset.UTC).format(UTC_FORMAT)

    /** RFC 5545 §3.3.11: escape backslash, semicolon, comma and newlines in TEXT values. */
    fun escapeText(s: String): String = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")
        .replace("\r", "")

    /** Minimal escape for parameter values such as `CN=` (no `"` or `;`). */
    fun escapeParam(s: String): String = s.replace("\"", "'").replace(";", " ")

    /**
     * RFC 5545 §3.1: content lines are at most 75 octets, continued on lines that start with a
     * space. Counted in UTF-8 octets, not chars, and split only between code points, so Hebrew
     * text stays within the limit and an emoji is never cut in half.
     */
    fun fold(line: String): String {
        if (line.length * 3 <= MAX_LINE_OCTETS) return line
        val sb = StringBuilder()
        var octets = 0
        var offset = 0
        while (offset < line.length) {
            val codePoint = line.codePointAt(offset)
            val size = utf8Size(codePoint)
            if (octets + size > MAX_LINE_OCTETS) {
                sb.append("\r\n ")
                octets = 1 // the leading space
            }
            sb.appendCodePoint(codePoint)
            octets += size
            offset += Character.charCount(codePoint)
        }
        return sb.toString()
    }

    /** Joins content lines with the CRLF line endings RFC 5545 requires, including a final one. */
    fun join(lines: List<String>): String = lines.joinToString("\r\n") + "\r\n"

    private fun utf8Size(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}
