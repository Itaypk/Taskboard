package dev.itayp.tasker.channel

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AttachmentIntakeTest {

    @Test
    fun `classifies what capture reads, by type or by name`() {
        assertThat(AttachmentIntake.classify("image/PNG", null)).isEqualTo(AttachmentIntake.Intake(AttachmentKind.IMAGE, "image/png"))
        assertThat(AttachmentIntake.classify("application/octet-stream", "a.pdf"))
            .isEqualTo(AttachmentIntake.Intake(AttachmentKind.DOCUMENT, AttachmentIntake.PDF))
        assertThat(AttachmentIntake.classify("text/plain; charset=utf-8", "notes.txt"))
            .isEqualTo(AttachmentIntake.Intake(AttachmentKind.TEXT, TextAttachments.PLAIN))
        assertThat(AttachmentIntake.classify("audio/ogg; codecs=opus", null))
            .isEqualTo(AttachmentIntake.Intake(AttachmentKind.AUDIO, "audio/ogg", "ogg"))
    }

    @Test
    fun `declines what it doesn't read`() {
        assertThat(AttachmentIntake.classify("video/mp4", null)).isNull()
        assertThat(AttachmentIntake.classify("audio/x-unknown", null)).isNull()
        assertThat(AttachmentIntake.classify(null, "archive.zip")).isNull()
    }

    @Test
    fun `an empty download, or binary under a text name, is nothing to read`() {
        val text = AttachmentIntake.Intake(AttachmentKind.TEXT, TextAttachments.PLAIN)
        assertThat(AttachmentIntake.attachment(text, ByteArray(0), "a.txt")).isNull()
        assertThat(AttachmentIntake.attachment(text, byteArrayOf(0, 1), "a.txt")).isNull()
        assertThat(AttachmentIntake.attachment(AttachmentIntake.Intake(AttachmentKind.IMAGE, "image/jpeg"), byteArrayOf(1), null)?.kind)
            .isEqualTo(AttachmentKind.IMAGE)
    }
}
