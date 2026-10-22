package dev.itayp.tasker.channel.inboundmail

import dev.itayp.tasker.config.InboundMailProperties
import jakarta.mail.BodyPart
import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Properties

/** What an inbound email carries that capture needs. Bodies are plain text, already trimmed to size. */
data class ParsedInboundMail(
    val messageId: String?,
    val subject: String,
    /** The `From:` address, lowercased. A claim only: anyone can write any `From:`. */
    val fromAddress: String?,
    val fromName: String?,
    val body: String,
    /** Parts that aren't the message text (files, images). Not read in v1 (GitHub issue #310). */
    val attachmentCount: Int,
    /** `Auto-Submitted:` other than `no`: an auto-reply, a bounce report, a robot. */
    val autoSubmitted: Boolean,
    /**
     * The `header.from` domain of a `dmarc=pass` in the `Authentication-Results` header our own MTA
     * added, or null without one. A pass means the `From:` domain really sent this message.
     */
    val dmarcPassDomain: String?,
)

class InboundMailParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Raw RFC 822 → [ParsedInboundMail], with jakarta.mail for the MIME structure and jsoup for
 * HTML-only bodies.
 *
 * Forwarded mail is the main use, and it comes in two shapes: inline (the forward's body quotes the
 * original, which needs nothing special) and as an attached `message/rfc822` (Outlook's "forward as
 * attachment"), whose text is unwrapped into the body under a forwarded-message marker, the way an
 * inline forward would show it.
 */
@Component
class InboundMailParser(properties: InboundMailProperties) {

    private val authservId = properties.effectiveAuthservId
    private val session: Session = Session.getInstance(Properties())

    fun parse(raw: ByteArray): ParsedInboundMail {
        val message = try {
            MimeMessage(session, ByteArrayInputStream(raw))
        } catch (e: MessagingException) {
            throw InboundMailParseException("unreadable message", e)
        }
        return try {
            val from = (message.from?.firstOrNull() as? InternetAddress)
            val content = Content()
            collect(message, content, depth = 0)
            ParsedInboundMail(
                messageId = message.messageID?.trim()?.takeIf { it.isNotEmpty() },
                subject = message.subject?.trim().orEmpty(),
                fromAddress = from?.address?.trim()?.lowercase()?.takeIf { it.contains('@') },
                fromName = from?.personal?.trim()?.takeIf { it.isNotEmpty() },
                body = content.text().take(MAX_BODY_CHARS),
                attachmentCount = content.attachments,
                autoSubmitted = isAutoSubmitted(message.getHeader("Auto-Submitted")),
                dmarcPassDomain = dmarcPassDomain(message.getHeader("Authentication-Results")),
            )
        } catch (e: MessagingException) {
            throw InboundMailParseException("malformed message", e)
        } catch (e: IOException) {
            throw InboundMailParseException("unreadable message part", e)
        }
    }

    /** Accumulates the text of a message tree: the first plain body wins over an HTML one. */
    private class Content {
        val plain = StringBuilder()
        val html = StringBuilder()
        var attachments = 0

        fun text(): String = (plain.takeIf { it.isNotBlank() } ?: html).toString().trim().replace(BLANK_LINES, "\n\n")
    }

    private fun collect(part: Part, content: Content, depth: Int) {
        if (depth > MAX_DEPTH) return
        val isAttachment = Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) || part.fileName != null
        when {
            part.isMimeType("multipart/alternative") -> {
                // Alternatives of one body: take the plain one if there is one, else the HTML one.
                val multipart = part.content as Multipart
                val parts = (0 until multipart.count).map { multipart.getBodyPart(it) }
                val chosen = parts.firstOrNull { it.isMimeType("text/plain") } ?: parts.firstOrNull()
                chosen?.let { collect(it, content, depth + 1) }
            }
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as Multipart
                for (i in 0 until multipart.count) collect(multipart.getBodyPart(i), content, depth + 1)
            }
            part.isMimeType("message/rfc822") -> {
                val inner = part.content as? MimeMessage ?: return
                val header = buildString {
                    append("\n\n").append(FORWARDED_MARKER).append('\n')
                    inner.from?.firstOrNull()?.let { append("From: ").append(it.toString()).append('\n') }
                    inner.subject?.let { append("Subject: ").append(it).append('\n') }
                    append('\n')
                }
                content.plain.append(header)
                content.html.append(header)
                collect(inner, content, depth + 1)
            }
            isAttachment -> content.attachments++
            part.isMimeType("text/plain") -> content.plain.append(textOf(part)).append('\n')
            part.isMimeType("text/html") -> content.html.append(htmlToText(textOf(part))).append('\n')
            // Inline images and other non-text parts without a filename: still not text.
            part !is MimeMessage && part is BodyPart -> content.attachments++
        }
    }

    /** A part in an unknown charset or with a broken encoding reads as empty rather than failing the message. */
    private fun textOf(part: Part): String =
        try {
            part.content as? String ?: ""
        } catch (_: IOException) {
            ""
        } catch (_: MessagingException) {
            ""
        }

    /** Only our MTA's header counts; the verifier strips every other one before adding its own. */
    private fun dmarcPassDomain(headers: Array<String>?): String? {
        for (header in headers.orEmpty()) {
            val clauses = MimeUtility.unfold(header).split(';')
            val id = clauses.first().trim().substringBefore(' ').lowercase()
            if (id != authservId) continue
            for (clause in clauses.drop(1)) {
                if (!DMARC_PASS.containsMatchIn(clause)) continue
                return HEADER_FROM.find(clause)?.groupValues?.get(1)?.lowercase()
            }
        }
        return null
    }

    private fun isAutoSubmitted(headers: Array<String>?): Boolean =
        headers.orEmpty().any { value ->
            value.substringBefore(';').substringBefore('(').trim().lowercase().let { it.isNotEmpty() && it != "no" }
        }

    private fun htmlToText(html: String): String {
        val document: Document = Jsoup.parse(html)
        document.select("script, style, head").remove()
        // Keep the line structure the markup implies: wholeText() turns <br> into a newline, but
        // runs block elements together.
        document.select("p, div, li, tr, h1, h2, h3, h4, h5, h6, blockquote").forEach { it.append("\n") }
        return document.body()?.wholeText().orEmpty().lines().joinToString("\n") { it.trim() }
    }

    companion object {
        /** Stored body size cap. Forwarded threads get long; the capture only needs the gist. */
        const val MAX_BODY_CHARS = 20_000
        const val FORWARDED_MARKER = "---------- Forwarded message ----------"
        private const val MAX_DEPTH = 10
        private val BLANK_LINES = Regex("\\n{3,}")
        private val DMARC_PASS = Regex("^\\s*dmarc\\s*=\\s*pass\\b", RegexOption.IGNORE_CASE)
        private val HEADER_FROM = Regex("header\\.from\\s*=\\s*([^\\s;()]+)", RegexOption.IGNORE_CASE)
    }
}
