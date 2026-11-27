package dev.itayp.tasker.channel.whatsapp

import dev.itayp.tasker.channel.AttachmentIntake
import dev.itayp.tasker.channel.AttachmentKind
import dev.itayp.tasker.channel.TextAttachments
import dev.itayp.tasker.channel.messaging.MediaFetch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WhatsAppMediaFetcherTest {

    private val client: WhatsAppClient = mock()
    private val fetcher = WhatsAppMediaFetcher(client)

    private fun stub(bytes: ByteArray, fileSize: Long? = bytes.size.toLong(), mimeType: String = "image/jpeg") {
        whenever(client.mediaInfo("m1")).thenReturn(WhatsAppMediaInfo("https://lookaside.test/m1", mimeType, fileSize))
        whenever(client.download(any(), any())).thenReturn(bytes)
    }

    private fun fetched(result: MediaFetch) = (result as MediaFetch.Fetched).media

    @Test
    fun `a photo comes back as an image with its caption`() {
        stub(byteArrayOf(1, 2, 3))

        val media = fetched(fetcher.fetch(WhatsAppMediaRef("m1", "image/jpeg", null, "receipt")))

        assertThat(media.caption).isEqualTo("receipt")
        assertThat(media.attachments.single().kind).isEqualTo(AttachmentKind.IMAGE)
        assertThat(media.attachments.single().mediaType).isEqualTo("image/jpeg")
        verify(client).download("https://lookaside.test/m1", AttachmentIntake.MAX_IMAGE_BYTES)
    }

    @Test
    fun `a voice note is audio labelled ogg`() {
        stub(byteArrayOf(1), mimeType = "audio/ogg; codecs=opus")

        val attachment = fetched(fetcher.fetch(WhatsAppMediaRef("m1", "audio/ogg; codecs=opus", null, null))).attachments.single()

        assertThat(attachment.kind).isEqualTo(AttachmentKind.AUDIO)
        assertThat(attachment.format).isEqualTo("ogg")
    }

    @Test
    fun `a PDF and a calendar invitation are read by their names`() {
        stub(byteArrayOf(1), mimeType = "application/octet-stream")
        assertThat(fetched(fetcher.fetch(WhatsAppMediaRef("m1", "application/octet-stream", "Ticket.PDF", null))).attachments.single().kind)
            .isEqualTo(AttachmentKind.DOCUMENT)

        stub("BEGIN:VCALENDAR\nSUMMARY:Dentist\nEND:VCALENDAR".toByteArray(), mimeType = "application/octet-stream")
        val invite = fetched(fetcher.fetch(WhatsAppMediaRef("m1", "application/octet-stream", "invite.ics", null))).attachments.single()
        assertThat(invite.kind).isEqualTo(AttachmentKind.TEXT)
        assertThat(invite.mediaType).isEqualTo(TextAttachments.CALENDAR)
    }

    @Test
    fun `an unreadable type is declined without any API call`() {
        val result = fetcher.fetch(WhatsAppMediaRef("m1", "application/zip", "photos.zip", null))

        assertThat(result).isEqualTo(MediaFetch.Rejected(AttachmentIntake.Rejection.UNSUPPORTED_TYPE))
        verify(client, never()).mediaInfo(any())
    }

    @Test
    fun `an oversized file is declined by its declared size, before downloading`() {
        stub(byteArrayOf(1), fileSize = AttachmentIntake.MAX_IMAGE_BYTES + 1L)

        assertThat(fetcher.fetch(WhatsAppMediaRef("m1", "image/jpeg", null, null)))
            .isEqualTo(MediaFetch.Rejected(AttachmentIntake.Rejection.TOO_LARGE))
        verify(client, never()).download(any(), any())
    }

    @Test
    fun `a file larger than declared is declined while reading`() {
        stub(ByteArray(AttachmentIntake.MAX_IMAGE_BYTES + 1), fileSize = null)

        assertThat(fetcher.fetch(WhatsAppMediaRef("m1", "image/jpeg", null, null)))
            .isEqualTo(MediaFetch.Rejected(AttachmentIntake.Rejection.TOO_LARGE))
    }

    @Test
    fun `a failed or empty download is reported as such`() {
        whenever(client.mediaInfo(any())).thenThrow(WhatsAppApiException(404, 100, RuntimeException()))
        assertThat(fetcher.fetch(WhatsAppMediaRef("m1", "image/jpeg", null, null)))
            .isEqualTo(MediaFetch.Rejected(AttachmentIntake.Rejection.DOWNLOAD_FAILED))

        whenever(client.mediaInfo(any())).thenReturn(WhatsAppMediaInfo("https://x", null, null))
        whenever(client.download(any(), any())).thenReturn(ByteArray(0))
        assertThat(fetcher.fetch(WhatsAppMediaRef("m1", "image/jpeg", null, null)))
            .isEqualTo(MediaFetch.Rejected(AttachmentIntake.Rejection.DOWNLOAD_FAILED))
    }

    @Test
    fun `binary under a text name is unsupported`() {
        stub(byteArrayOf(0, 1, 2), mimeType = "text/plain")

        assertThat(fetcher.fetch(WhatsAppMediaRef("m1", "text/plain", "notes.txt", null)))
            .isEqualTo(MediaFetch.Rejected(AttachmentIntake.Rejection.UNSUPPORTED_TYPE))
    }
}
