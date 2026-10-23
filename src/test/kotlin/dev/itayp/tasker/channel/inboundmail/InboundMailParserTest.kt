package dev.itayp.tasker.channel.inboundmail

import dev.itayp.tasker.config.InboundMailProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
        assertThat(mail.attachmentCount).isZero()
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
            Content-Type: message/rfc822
            Content-Disposition: attachment

            From: Billing <billing@shop.example>
            Subject: Invoice 42
            Content-Type: text/plain; charset=UTF-8

            Amount due: 10 EUR
            --m1--
            """,
        )

        assertThat(mail.attachmentCount).isEqualTo(1)
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
}
