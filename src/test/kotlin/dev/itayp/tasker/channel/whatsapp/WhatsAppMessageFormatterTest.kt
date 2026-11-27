package dev.itayp.tasker.channel.whatsapp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WhatsAppMessageFormatterTest {

    @Test
    fun `uses WhatsApp markup, keeping markers against the text`() {
        assertThat(WhatsAppMessageFormatter.bold("Buy milk")).isEqualTo("*Buy milk*")
        assertThat(WhatsAppMessageFormatter.italic(" maybe ")).isEqualTo(" _maybe_ ")
        assertThat(WhatsAppMessageFormatter.bold("  ")).isEqualTo("  ")
        assertThat(WhatsAppMessageFormatter.bulletList(listOf("one", "two"))).isEqualTo("- one\n- two")
    }

    @Test
    fun `has nothing to escape`() {
        assertThat(WhatsAppMessageFormatter.escape("<b> & *x*")).isEqualTo("<b> & *x*")
    }
}
