package dev.itayp.tasker.channel

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TextAttachmentsTest {

    @Test
    fun `tells text files and invitations by type or name`() {
        assertThat(TextAttachments.mediaTypeOf("text/plain", null)).isEqualTo(TextAttachments.PLAIN)
        assertThat(TextAttachments.mediaTypeOf("application/octet-stream", "a.txt")).isEqualTo(TextAttachments.PLAIN)
        assertThat(TextAttachments.mediaTypeOf("application/ics", null)).isEqualTo(TextAttachments.CALENDAR)
        assertThat(TextAttachments.mediaTypeOf("application/octet-stream", "Invite.ICS")).isEqualTo(TextAttachments.CALENDAR)
        assertThat(TextAttachments.mediaTypeOf("application/octet-stream", "a.bin")).isNull()
        assertThat(TextAttachments.mediaTypeOf("application/msword", "a.doc")).isNull()
    }

    @Test
    fun `unfolds an invitation before decoding, so a fold inside a character survives`() {
        // "Café" with the fold between the two bytes of "é".
        val bytes = byteArrayOf(*"SUMMARY:Caf".toByteArray(), 0xC3.toByte(), '\r'.code.toByte(), '\n'.code.toByte(), ' '.code.toByte(), 0xA9.toByte())

        val attachment = TextAttachments.of(bytes, TextAttachments.CALENDAR, "invite.ics")

        assertThat(TextAttachments.textOf(attachment!!)).isEqualTo("SUMMARY:Café")
    }

    @Test
    fun `cuts a long file off, and refuses binary posing as text`() {
        val long = TextAttachments.of("x".repeat(TextAttachments.MAX_CHARS + 1).toByteArray(), TextAttachments.PLAIN, null)!!
        assertThat(TextAttachments.textOf(long)).hasSize(TextAttachments.MAX_CHARS + TextAttachments.CUT_OFF_MARKER.length)

        assertThat(TextAttachments.of(byteArrayOf(0x50, 0x4B, 0, 3), TextAttachments.PLAIN, "x.txt")).isNull()
    }
}
