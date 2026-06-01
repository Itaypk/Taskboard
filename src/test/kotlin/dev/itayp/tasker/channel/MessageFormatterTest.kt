package dev.itayp.tasker.channel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageFormatterTest {

    @Test
    fun `html escape converts the markup-significant characters`() {
        assertEquals("a &amp; b &lt;c&gt;", HtmlMessageFormatter.escape("a & b <c>"))
    }

    @Test
    fun `html bold and italic wrap and escape their argument`() {
        assertEquals("<b>1 &lt; 2</b>", HtmlMessageFormatter.bold("1 < 2"))
        assertEquals("<i>a &amp; b</i>", HtmlMessageFormatter.italic("a & b"))
    }

    @Test
    fun `html bullet list joins lines with an escaped bullet prefix`() {
        assertEquals(
            "• first\n• &lt;second&gt;",
            HtmlMessageFormatter.bulletList(listOf("first", "<second>")),
        )
    }

    @Test
    fun `html guidance advertises the supported tags and never mentions markdown as allowed`() {
        val guidance = HtmlMessageFormatter.promptGuidance()
        assertTrue(guidance.contains("<b>"), "should show the bold tag example")
        assertTrue(guidance.contains("<i>"), "should show the italic tag example")
        assertTrue(guidance.contains("never Markdown", ignoreCase = true))
    }

    @Test
    fun `plain text formatter strips emphasis, leaves text untouched, and escapes nothing`() {
        assertEquals("important", PlainTextMessageFormatter.bold("important"))
        assertEquals("a & b <c>", PlainTextMessageFormatter.escape("a & b <c>"))
    }

    @Test
    fun `plain text guidance instructs plain text only`() {
        val guidance = PlainTextMessageFormatter.promptGuidance()
        assertTrue(guidance.contains("plain text", ignoreCase = true))
        assertFalse(guidance.contains("<b>"))
    }
}
