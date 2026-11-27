package dev.itayp.tasker.channel.whatsapp

import dev.itayp.tasker.channel.FormattingFeature
import dev.itayp.tasker.channel.MessageFormatter

/**
 * WhatsApp's own markup: `*bold*`, `_italic_`, `- ` lists. It has no escape syntax, so [escape]
 * returns the text unchanged; a stray `*` or `_` in a task title is cosmetic at worst.
 */
object WhatsAppMessageFormatter : MessageFormatter {
    override val features = setOf(FormattingFeature.BOLD, FormattingFeature.ITALIC, FormattingFeature.BULLET_LIST)

    override fun bold(text: String) = wrap("*", text)

    override fun italic(text: String) = wrap("_", text)

    override fun bulletList(items: List<String>) = items.joinToString("\n") { "- $it" }

    override fun escape(text: String) = text

    // WhatsApp only renders a marker that hugs the text: "* x *" stays literal. Keep the edges
    // outside, and never wrap an empty string (which would print a bare "**").
    private fun wrap(marker: String, text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text
        val leading = text.substring(0, text.indexOf(trimmed))
        val trailing = text.substring(leading.length + trimmed.length)
        return "$leading$marker$trimmed$marker$trailing"
    }
}
