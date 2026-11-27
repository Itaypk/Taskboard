package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.channel.AttachmentIntake
import dev.itayp.tasker.channel.AttachmentKind
import dev.itayp.tasker.channel.ChannelInbound
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.objects.message.Message
import org.telegram.telegrambots.meta.generics.TelegramClient

/**
 * Turns a Telegram message carrying a photo, a voice note or a file (an image, a PDF, a text file or
 * a calendar invitation) into the channel-agnostic [ChannelInbound.Media] the capture flow
 * understands.
 *
 * Telegram hands us only a `file_id`; the bytes need a `getFile` round-trip for the server-side path
 * and then a download from the file endpoint (which embeds the bot token in its URL — a reason we
 * download the bytes rather than handing the URL to OpenRouter). See
 * https://rubenlagus.github.io/TelegramBotsDocumentation/faq.html#how-to-download-photo.
 *
 * Bytes are returned to the caller and never written to disk or logged; only sizes and media types
 * appear in logs.
 */
@Component
@ConditionalOnTelegramBot
class TelegramMediaExtractor(
    private val telegramClient: TelegramClient,
) {
    private val log = LoggerFactory.getLogger(TelegramMediaExtractor::class.java)

    /** What a message turned out to hold. [None] means "not a media message" — let text routing have it. */
    sealed interface Extraction {
        data object None : Extraction
        data class Media(val inbound: ChannelInbound.Media) : Extraction
        /** Recognized media we won't take: an oversized file, or a type we don't capture from. */
        data class Rejected(val reason: Reason, val kind: AttachmentKind?) : Extraction
    }

    enum class Reason { TOO_LARGE, UNSUPPORTED_TYPE, DOWNLOAD_FAILED }

    /**
     * Whether [message] carries something we might capture from — checked before the user is
     * resolved, so it must stay cheap (no API calls). [extract] does the actual work.
     */
    fun carriesMedia(message: Message): Boolean =
        message.hasPhoto() || message.hasVoice() || message.hasAudio() ||
            // A GIF arrives as an animation that is also a document; it isn't a file the user sent us.
            (message.hasDocument() && !message.hasAnimation())

    fun extract(message: Message): Extraction {
        val caption = message.caption?.trim()?.takeIf { it.isNotBlank() }
        return when {
            message.hasPhoto() -> {
                // Telegram sends one entry per rendered size; the last/largest is the one worth reading.
                val largest = message.photo.maxByOrNull { it.width ?: 0 } ?: return Extraction.None
                download(largest.fileId, largest.fileSize?.toLong(), AttachmentKind.IMAGE, "image/jpeg", null, caption)
            }

            message.hasVoice() -> {
                val voice = message.voice
                val format = AttachmentIntake.audioFormat(voice.mimeType) ?: return unsupported(AttachmentKind.AUDIO, voice.mimeType)
                download(voice.fileId, voice.fileSize, AttachmentKind.AUDIO, voice.mimeType ?: "audio/ogg", format, caption)
            }

            message.hasAudio() -> {
                val audio = message.audio
                val format = AttachmentIntake.audioFormat(audio.mimeType) ?: return unsupported(AttachmentKind.AUDIO, audio.mimeType)
                download(audio.fileId, audio.fileSize, AttachmentKind.AUDIO, audio.mimeType ?: "audio/mpeg", format, caption)
            }

            // A file: an image sent as one (common when the sender kept the original quality), a
            // PDF, or a text file or calendar invitation, read as text. Anything else gets a reply
            // naming what we do read, rather than silence.
            message.hasDocument() -> {
                val document = message.document
                val filename = document.fileName?.trim()?.takeIf { it.isNotEmpty() }
                // Audio sent as a file isn't taken: Telegram delivers real audio as `audio`.
                val intake = AttachmentIntake.classify(document.mimeType, filename)
                    ?.takeIf { it.kind != AttachmentKind.AUDIO }
                    ?: return unsupported(null, document.mimeType)
                download(document.fileId, document.fileSize, intake.kind, intake.mediaType, null, caption, filename)
            }

            else -> Extraction.None
        }
    }

    private fun download(
        fileId: String,
        declaredSize: Long?,
        kind: AttachmentKind,
        mediaType: String,
        format: String?,
        caption: String?,
        filename: String? = null,
    ): Extraction {
        val limit = AttachmentIntake.limitFor(kind)
        if (declaredSize != null && declaredSize > limit) {
            log.info("declining {} attachment: {}B exceeds the {}B limit", kind, declaredSize, limit)
            return Extraction.Rejected(Reason.TOO_LARGE, kind)
        }
        val bytes = try {
            val file = telegramClient.execute(GetFile.builder().fileId(fileId).build())
            telegramClient.downloadFileAsStream(file).use { it.readNBytes(limit + 1) }
        } catch (e: Exception) {
            log.warn("failed to download {} attachment from Telegram: {}", kind, e.message)
            return Extraction.Rejected(Reason.DOWNLOAD_FAILED, kind)
        }
        // readNBytes(limit + 1) is the size check for a file whose declared size was missing or wrong:
        // one byte over the limit means there was more to read.
        if (bytes.size > limit) {
            log.info("declining {} attachment: stream exceeded the {}B limit", kind, limit)
            return Extraction.Rejected(Reason.TOO_LARGE, kind)
        }
        if (bytes.isEmpty()) return Extraction.Rejected(Reason.DOWNLOAD_FAILED, kind)
        log.debug("downloaded {} attachment ({}, {}B) for capture", kind, mediaType, bytes.size)
        // Null for binary under a text name: nothing to read.
        val attachment = AttachmentIntake.attachment(AttachmentIntake.Intake(kind, mediaType, format), bytes, filename)
            ?: return unsupported(kind, mediaType)
        return Extraction.Media(ChannelInbound.Media(attachments = listOf(attachment), caption = caption))
    }

    private fun unsupported(kind: AttachmentKind?, mimeType: String?): Extraction {
        log.info("declining {} attachment: unsupported media type {}", kind, mimeType)
        return Extraction.Rejected(Reason.UNSUPPORTED_TYPE, kind)
    }
}
