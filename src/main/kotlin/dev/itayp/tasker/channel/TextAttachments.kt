package dev.itayp.tasker.channel

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Plain text files and calendar invitations: the attachments capture reads as text in the prompt
 * ([AttachmentKind.TEXT]) instead of sending them to the model as files. OpenRouter's `file` input
 * is for PDFs; text needs no modality at all, so any model reads these.
 *
 * Invitations stay raw iCalendar: models read it well enough that a parser isn't worth it (GitHub
 * issue #316). Only the properties that are bulk without information are dropped.
 */
object TextAttachments {
    const val PLAIN = "text/plain"
    const val CALENDAR = "text/calendar"

    /** What the model reads of one file. A forwarded thread's worth; the gist is near the top. */
    const val MAX_CHARS = 10_000
    const val CUT_OFF_MARKER = "\n[…the rest of the file is cut off]"

    private val CALENDAR_TYPES = setOf(CALENDAR, "application/ics", "text/x-vcalendar")

    /**
     * Inline attachments (`ATTACH;ENCODING=BASE64`) and Outlook's HTML copy of the description
     * (`X-ALT-DESC`): both can be far longer than the rest of the invitation, and add nothing.
     */
    private val DROPPED_CALENDAR_PROPERTIES = Regex("^(ATTACH|X-ALT-DESC)[;:]", RegexOption.IGNORE_CASE)

    /**
     * The text media type for an attachment declared as [mediaType] (lowercase, no parameters) and
     * named [filename], or null when it isn't one we read as text. A `.ics` name counts whatever
     * the declared type: invitations are often sent as `application/octet-stream`.
     */
    fun mediaTypeOf(mediaType: String, filename: String?): String? {
        val extension = filename?.substringAfterLast('.', "")?.lowercase()
        return when {
            mediaType in CALENDAR_TYPES || extension == "ics" -> CALENDAR
            mediaType == PLAIN -> PLAIN
            mediaType == "application/octet-stream" && extension == "txt" -> PLAIN
            else -> null
        }
    }

    /**
     * Decodes [bytes] in [charset] (UTF-8, iCalendar's default, when unknown) into an attachment of
     * [mediaType] (one [mediaTypeOf] returned). Null when there is no text in it: empty, or binary
     * mislabeled as text.
     */
    fun of(bytes: ByteArray, mediaType: String, filename: String?, charset: Charset? = null): InboundAttachment? {
        val isCalendar = mediaType == CALENDAR
        // Folding may split a multi-byte character, so unfold before decoding.
        val decoded = String(if (isCalendar) unfold(bytes) else bytes, charset ?: StandardCharsets.UTF_8)
        if (decoded.contains('\u0000')) return null
        var text = decoded.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        if (isCalendar) text = text.lineSequence().filterNot { DROPPED_CALENDAR_PROPERTIES.containsMatchIn(it) }.joinToString("\n")
        text = text.trim()
        if (text.isEmpty()) return null
        if (text.length > MAX_CHARS) text = text.take(MAX_CHARS) + CUT_OFF_MARKER
        return InboundAttachment(AttachmentKind.TEXT, text.toByteArray(StandardCharsets.UTF_8), mediaType, filename = filename)
    }

    /** The attachment's text. [of] stores it as UTF-8. */
    fun textOf(attachment: InboundAttachment): String = attachment.bytes.toString(StandardCharsets.UTF_8)

    /** RFC 5545 §3.1: a line break followed by a space or a tab continues the previous line. */
    private fun unfold(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            val breakLength = when {
                b == CR && i + 1 < bytes.size && bytes[i + 1] == LF -> 2
                b == LF -> 1
                else -> 0
            }
            if (breakLength > 0 && i + breakLength < bytes.size && bytes[i + breakLength].let { it == SPACE || it == TAB }) {
                i += breakLength + 1
                continue
            }
            out.write(b.toInt())
            i++
        }
        return out.toByteArray()
    }

    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()
    private const val SPACE = ' '.code.toByte()
    private const val TAB = '\t'.code.toByte()
}
