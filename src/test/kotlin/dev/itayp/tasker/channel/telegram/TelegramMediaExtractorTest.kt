package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.channel.AttachmentKind
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.objects.Document
import org.telegram.telegrambots.meta.api.objects.Voice
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize
import org.telegram.telegrambots.meta.generics.TelegramClient
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelegramMediaExtractorTest {

    private val telegramClient: TelegramClient = mock()
    private val extractor = TelegramMediaExtractor(telegramClient)

    private val telegramFile = mock<org.telegram.telegrambots.meta.api.objects.File>()

    private fun stubDownload(bytes: ByteArray) {
        whenever(telegramClient.execute(any<GetFile>())).doReturn(telegramFile)
        whenever(telegramClient.downloadFileAsStream(telegramFile)).doReturn(ByteArrayInputStream(bytes))
    }

    private fun photoMessage(vararg sizes: PhotoSize, caption: String? = null): Message = mock {
        on { hasPhoto() } doReturn true
        on { photo } doReturn sizes.toList()
        on { getCaption() } doReturn caption
    }

    private fun photoSize(fileId: String, width: Int, fileSize: Int?): PhotoSize = mock {
        on { getFileId() } doReturn fileId
        on { getWidth() } doReturn width
        on { getFileSize() } doReturn fileSize
    }

    @Test
    fun `takes the largest photo size and downloads it`() {
        stubDownload(byteArrayOf(1, 2, 3, 4))
        val message = photoMessage(
            photoSize("small", width = 90, fileSize = 900),
            photoSize("large", width = 1280, fileSize = 90_000),
            caption = "add this",
        )

        val extraction = assertIs<TelegramMediaExtractor.Extraction.Media>(extractor.extract(message))

        val attachment = extraction.inbound.attachments.single()
        assertEquals(AttachmentKind.IMAGE, attachment.kind)
        assertEquals("image/jpeg", attachment.mediaType)
        assertEquals(4, attachment.bytes.size)
        assertEquals("add this", extraction.inbound.caption)
        val getFile = argumentCaptor<GetFile>()
        verify(telegramClient).execute(getFile.capture())
        assertEquals("large", getFile.firstValue.fileId)
    }

    @Test
    fun `rejects a photo whose declared size is over the limit without downloading it`() {
        val message = photoMessage(
            photoSize("huge", width = 4000, fileSize = TelegramMediaExtractor.MAX_IMAGE_BYTES + 1),
        )

        val rejected = assertIs<TelegramMediaExtractor.Extraction.Rejected>(extractor.extract(message))

        assertEquals(TelegramMediaExtractor.Reason.TOO_LARGE, rejected.reason)
        verify(telegramClient, never()).execute(any<GetFile>())
    }

    @Test
    fun `rejects a file that lied about its size, caught while reading the stream`() {
        stubDownload(ByteArray(TelegramMediaExtractor.MAX_IMAGE_BYTES + 10))
        val message = photoMessage(photoSize("sneaky", width = 1280, fileSize = null))

        val rejected = assertIs<TelegramMediaExtractor.Extraction.Rejected>(extractor.extract(message))

        assertEquals(TelegramMediaExtractor.Reason.TOO_LARGE, rejected.reason)
    }

    @Test
    fun `maps a voice note's mime type to the codec label OpenRouter expects`() {
        stubDownload(byteArrayOf(9, 9))
        val voice: Voice = mock {
            on { getFileId() } doReturn "voice-1"
            on { getMimeType() } doReturn "audio/ogg"
            on { getFileSize() } doReturn 2L
        }
        val message: Message = mock {
            on { hasVoice() } doReturn true
            on { getVoice() } doReturn voice
        }

        val extraction = assertIs<TelegramMediaExtractor.Extraction.Media>(extractor.extract(message))

        val attachment = extraction.inbound.attachments.single()
        assertEquals(AttachmentKind.AUDIO, attachment.kind)
        assertEquals("ogg", attachment.format)
    }

    @Test
    fun `declines audio in a codec we have no label for rather than guessing one`() {
        val voice: Voice = mock {
            on { getFileId() } doReturn "voice-1"
            on { getMimeType() } doReturn "audio/amr"
        }
        val message: Message = mock {
            on { hasVoice() } doReturn true
            on { getVoice() } doReturn voice
        }

        val rejected = assertIs<TelegramMediaExtractor.Extraction.Rejected>(extractor.extract(message))

        assertEquals(TelegramMediaExtractor.Reason.UNSUPPORTED_TYPE, rejected.reason)
        verify(telegramClient, never()).execute(any<GetFile>())
    }

    @Test
    fun `takes an image sent as a document but ignores other document types`() {
        stubDownload(byteArrayOf(7))
        val png: Document = mock {
            on { getFileId() } doReturn "doc-1"
            on { getMimeType() } doReturn "image/png"
            on { getFileSize() } doReturn 1L
        }
        val pngMessage: Message = mock {
            on { hasDocument() } doReturn true
            on { getDocument() } doReturn png
        }
        val extraction = assertIs<TelegramMediaExtractor.Extraction.Media>(extractor.extract(pngMessage))
        assertEquals("image/png", extraction.inbound.attachments.single().mediaType)

        val spreadsheet: Document = mock { on { getMimeType() } doReturn "application/vnd.ms-excel" }
        val otherMessage: Message = mock {
            on { hasDocument() } doReturn true
            on { getDocument() } doReturn spreadsheet
        }
        assertIs<TelegramMediaExtractor.Extraction.None>(extractor.extract(otherMessage))
    }

    @Test
    fun `carriesMedia recognizes photos and voice notes but not plain messages`() {
        assertEquals(true, extractor.carriesMedia(photoMessage(photoSize("a", 100, 100))))
        assertEquals(false, extractor.carriesMedia(mock<Message>()))
    }
}
