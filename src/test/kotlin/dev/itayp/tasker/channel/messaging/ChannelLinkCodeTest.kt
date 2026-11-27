package dev.itayp.tasker.channel.messaging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChannelLinkCodeTest {

    @Test
    fun `finds a dashed code anywhere in a short message, in any case`() {
        assertThat(ChannelLinkService.extractCode("Link my Backlog account: ABCD-EF27")).isEqualTo("ABCDEF27")
        assertThat(ChannelLinkService.extractCode("abcd-ef27")).isEqualTo("ABCDEF27")
    }

    @Test
    fun `takes an undashed code only as the whole message`() {
        assertThat(ChannelLinkService.extractCode("  abcdef27 ")).isEqualTo("ABCDEF27")
        assertThat(ChannelLinkService.extractCode("call the plumber tomorrow")).isNull()
        assertThat(ChannelLinkService.extractCode("homework")).isNull()
        assertThat(ChannelLinkService.extractCode("ABCD-EFGH")).isNull()
    }

    @Test
    fun `ignores look-alikes and long messages`() {
        assertThat(ChannelLinkService.extractCode("ABCD-EF19")).isNull()
        assertThat(ChannelLinkService.extractCode("XABCD-EF27")).isNull()
        assertThat(ChannelLinkService.extractCode("buy milk ".repeat(30) + "ABCD-EF27")).isNull()
    }

    @Test
    fun `masks a display down to its last four characters`() {
        assertThat(ChannelLinkService.mask("972546560978")).isEqualTo("•••• 0978")
        assertThat(ChannelLinkService.mask("123")).isEqualTo("123")
    }
}
