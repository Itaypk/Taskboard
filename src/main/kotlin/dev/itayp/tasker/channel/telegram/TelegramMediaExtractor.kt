package dev.itayp.tasker.channel.telegram

import dev.itayp.tasker.channel.AttachmentKind
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.InboundAttachment
import dev.itayp.tasker.channel.TextAttachments
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
                val format = audioFormat(voice.mimeType) ?: return unsupported(AttachmentKind.AUDIO, voice.mimeType)
                download(voice.fileId, voice.fileSize, AttachmentKind.AUDIO, voice.mimeType ?: "audio/ogg", format, caption)
            }

            message.hasAudio() -> {
                val audio = message.audio
                val format = audioFormat(audio.mimeType) ?: return unsupported(AttachmentKind.AUDIO, audio.mimeType)
                download(audio.fileId, audio.fileSize, AttachmentKind.AUDIO, audio.mimeType ?: "audio/mpeg", format, caption)
            }

            // A file: an image sent as one (common when the sender kept the original quality), a
            // PDF, or a text file or calendar invitation, read as text. Anything else gets a reply
            // naming what we do read, rather than silence.
            message.hasDocument() -> {
                val document = message.document
                val mimeType = document.mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                val filename = document.fileName?.trim()?.takeIf { it.isNotEmpty() }
                val textType = TextAttachments.mediaTypeOf(mimeType, filename)
                when {
                    mimeType.startsWith("image/") ->
                        download(document.fileId, document.fileSize, AttachmentKind.IMAGE, mimeType, null, caption)
                    mimeType == PDF || filename?.lowercase()?.endsWith(".pdf") == true ->
                        download(document.fileId, document.fileSize, AttachmentKind.DOCUMENT, PDF, null, caption, filename)
                    textType != null ->
                        download(document.fileId, document.fileSize, AttachmentKind.TEXT, textType, null, caption, filename)
                    else -> unsupported(null, mimeType)
                }
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
        val limit = limitFor(kind)
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
        val attachment = if (kind == AttachmentKind.TEXT) {
            // Empty, or binary under a text name: nothing to read.
            TextAttachments.of(bytes, mediaType, filename) ?: return unsupported(kind, mediaType)
        } else {
            InboundAttachment(kind, bytes, mediaType, format, filename)
        }
        return Extraction.Media(ChannelInbound.Media(attachments = listOf(attachment), caption = caption))
    }

    private fun unsupported(kind: AttachmentKind?, mimeType: String?): Extraction {
        log.info("declining {} attachment: unsupported media type {}", kind, mimeType)
        return Extraction.Rejected(Reason.UNSUPPORTED_TYPE, kind)
    }

    private fun limitFor(kind: AttachmentKind): Int = when (kind) {
        AttachmentKind.IMAGE -> MAX_IMAGE_BYTES
        AttachmentKind.AUDIO -> MAX_AUDIO_BYTES
        AttachmentKind.DOCUMENT, AttachmentKind.TEXT -> MAX_DOCUMENT_BYTES
    }

    /**
     * The codec label OpenRouter wants for an `input_audio` part, from Telegram's declared mime type.
     * Telegram voice notes are always OGG/Opus; the rest cover audio files a user might forward.
     * An unmapped type is declined rather than guessed — a wrong label is a provider-side error.
     */
    private fun audioFormat(mimeType: String?): String? = when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/ogg", "audio/opus", "audio/x-opus+ogg" -> "ogg"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/aac" -> "aac"
        else -> null
    }

    companion object {
        /**
         * Caps on what we'll pull down and base64 into a prompt. Telegram compresses photos hard
         * (a phone snapshot lands well under a megabyte), and a voice note is a few hundred KB per
         * minute — so these are guardrails against a pathological file, not everyday limits.
         * Base64 inflates the payload by a third on top of these.
         */
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        const val MAX_AUDIO_BYTES = 5 * 1024 * 1024

        /** PDFs and text files, as on inbound email. A text file is cut to `TextAttachments.MAX_CHARS` after. */
        const val MAX_DOCUMENT_BYTES = 5 * 1024 * 1024
        private const val PDF = "application/pdf"
    }
}
