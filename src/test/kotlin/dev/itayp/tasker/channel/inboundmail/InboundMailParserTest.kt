package dev.itayp.tasker.channel.inboundmail

import dev.itayp.tasker.config.InboundMailProperties
import dev.itayp.tasker.channel.AttachmentKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

class InboundMailParserTest {

    private val parser = InboundMailParser(
        InboundMailProperties(enabled = true, domain = "m.example.com", deliveryToken = "x".repeat(32)),
    )

    private fun parse(message: String) = parser.parse(message.trimIndent().replace("\n", "\r\n").toByteArray())

    @Test
    fun `reads a plain text message`() {
        val mail = parse(
            """
            Message-ID: <abc@mail.example>
            From: Joe Bloggs <Joe@Example.org>
            To: tasks.k7f3x5q2mz@m.example.com
            Subject: Renew the passport
            Content-Type: text/plain; charset=UTF-8

            Expires in March.
            """,
        )

        assertThat(mail.messageId).isEqualTo("<abc@mail.example>")
        assertThat(mail.subject).isEqualTo("Renew the passport")
        assertThat(mail.fromAddress).isEqualTo("joe@example.org")
        assertThat(mail.fromName).isEqualTo("Joe Bloggs")
        assertThat(mail.body).isEqualTo("Expires in March.")
        assertThat(mail.attachments).isEmpty()
        assertThat(mail.unreadAttachments).isEmpty()
        assertThat(mail.autoSubmitted).isFalse()
        assertThat(mail.dmarcPassDomain).isNull()
    }

    @Test
    fun `prefers the plain alternative, and turns an HTML-only body into text with its line breaks`() {
        val alternative = parse(
            """
            From: a@example.org
            Subject: Alt
            Content-Type: multipart/alternative; boundary="b1"

            --b1
            Content-Type: text/plain; charset=UTF-8

            Plain version
            --b1
            Content-Type: text/html; charset=UTF-8

            <p>HTML version</p>
            --b1--
            """,
        )
        assertThat(alternative.body).isEqualTo("Plain version")

        val htmlOnly = parse(
            """
            From: a@example.org
            Subject: Html
            Content-Type: text/html; charset=UTF-8

            <html><head><style>p{color:red}</style></head><body><p>First line</p><p>Second &amp; last<br>line</p><script>x()</script></body></html>
            """,
        )
        assertThat(htmlOnly.body).isEqualTo("First line\nSecond & last\nline")
    }

    @Test
    fun `counts attachments without reading them, and unwraps a message forwarded as an attachment`() {
        val mail = parse(
            """
            From: me@example.org
            Subject: Fwd: Invoice
            Content-Type: multipart/mixed; boundary="m1"

            --m1
            Content-Type: text/plain; charset=UTF-8

            Please pay this.
            --m1
            Content-Type: application/pdf; name="invoice.pdf"
            Content-Disposition: attachment; filename="invoice.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0xLjQK
            --m1
            Content-Type: application/vnd.ms-excel
            Content-Disposition: attachment; filename="sheet.xls"

            x
            --m1
            Content-Type: message/rfc822
            Content-Disposition: attachment

            From: Billing <billing@shop.example>
            Subject: Invoice 42
            Content-Type: text/plain; charset=UTF-8

            Amount due: 10 EUR
            --m1--
            """,
        )

        assertThat(mail.attachments.map { it.kind to it.filename }).containsExactly(AttachmentKind.DOCUMENT to "invoice.pdf")
        assertThat(mail.attachments.single().mediaType).isEqualTo("application/pdf")
        assertThat(mail.unreadAttachments).containsExactly("sheet.xls")
        assertThat(mail.body)
            .startsWith("Please pay this.")
            .contains(InboundMailParser.FORWARDED_MARKER)
            .contains("Subject: Invoice 42")
            .endsWith("Amount due: 10 EUR")
    }

    @Test
    fun `flags auto-submitted mail, but not Auto-Submitted no`() {
        val auto = parse("From: a@example.org\nAuto-Submitted: auto-replied\nSubject: Out of office\n\nAway")
        val manual = parse("From: a@example.org\nAuto-Submitted: no\nSubject: Hi\n\nHi")

        assertThat(auto.autoSubmitted).isTrue()
        assertThat(manual.autoSubmitted).isFalse()
    }

    @Test
    fun `trusts only a DMARC pass in the Authentication-Results header with our authserv-id`() {
        val ours = parse(
            """
            Authentication-Results: m.example.com;
            	dkim=pass header.d=example.org header.s=s1;
            	spf=pass smtp.mailfrom=me@example.org;
            	dmarc=pass (policy=none) header.from=Example.org
            From: me@example.org
            Subject: Hi

            Hi
            """,
        )
        val foreign = parse(
            """
            Authentication-Results: mx.attacker.example; dmarc=pass header.from=example.org
            From: me@example.org
            Subject: Hi

            Hi
            """,
        )
        val failed = parse(
            """
            Authentication-Results: m.example.com; dmarc=fail (policy=reject) header.from=example.org
            From: me@example.org
            Subject: Hi

            Hi
            """,
        )

        assertThat(ours.dmarcPassDomain).isEqualTo("example.org")
        assertThat(foreign.dmarcPassDomain).isNull()
        assertThat(failed.dmarcPassDomain).isNull()
    }

    @Test
    fun `caps the body`() {
        val mail = parse("From: a@example.org\nSubject: Long\n\n" + "x".repeat(InboundMailParser.MAX_BODY_CHARS + 100))

        assertThat(mail.body).hasSize(InboundMailParser.MAX_BODY_CHARS)
    }

    /** A multipart/mixed message with a text body and one part per [parts] entry (headers, bytes). */
    private fun withParts(vararg parts: Pair<String, ByteArray>): ParsedInboundMail {
        val body = StringBuilder("From: me@example.org\r\nSubject: Parts\r\nContent-Type: multipart/mixed; boundary=\"p\"\r\n\r\n")
        body.append("--p\r\nContent-Type: text/plain\r\n\r\nSee attached.\r\n")
        for ((headers, bytes) in parts) {
            body.append("--p\r\n").append(headers.replace("\n", "\r\n")).append("\r\nContent-Transfer-Encoding: base64\r\n\r\n")
            body.append(Base64.getMimeEncoder().encodeToString(bytes)).append("\r\n")
        }
        body.append("--p--\r\n")
        return parser.parse(body.toString().toByteArray())
    }

    private fun image(name: String, size: Int = 20_000) =
        "Content-Type: image/jpeg\nContent-Disposition: attachment; filename=\"$name\"" to ByteArray(size) { 1 }

    @Test
    fun `drops small images as decoration, without naming them`() {
        val mail = withParts(
            "Content-Type: image/png\nContent-ID: <logo>\nContent-Disposition: inline" to ByteArray(2_000) { 1 },
            image("photo.jpg"),
        )

        assertThat(mail.attachments.map { it.filename }).containsExactly("photo.jpg")
        assertThat(mail.attachments.single().mediaType).isEqualTo("image/jpeg")
        assertThat(mail.unreadAttachments).isEmpty()
    }

    @Test
    fun `names attachments over the size cap or past the count cap`() {
        val mail = withParts(
            image("huge.jpg", InboundMailParser.MAX_ATTACHMENT_BYTES + 1),
            image("1.jpg"), image("2.jpg"), image("3.jpg"), image("4.jpg"), image("5.jpg"),
        )

        assertThat(mail.attachments.map { it.filename }).containsExactly("1.jpg", "2.jpg", "3.jpg", "4.jpg")
        assertThat(mail.unreadAttachments).containsExactly("huge.jpg", "5.jpg")
    }

    @Test
    fun `takes a PDF sent as a generic binary by its name, and an unnamed part by its media type`() {
        val mail = withParts(
            "Content-Type: application/octet-stream\nContent-Disposition: attachment; filename=\"Ticket.PDF\"" to "%PDF-1.4".toByteArray(),
            "Content-Type: application/zip\nContent-Disposition: attachment" to byteArrayOf(1),
        )

        assertThat(mail.attachments.single().kind).isEqualTo(AttachmentKind.DOCUMENT)
        assertThat(mail.attachments.single().mediaType).isEqualTo("application/pdf")
        assertThat(mail.unreadAttachments).containsExactly("application/zip")
    }
}
