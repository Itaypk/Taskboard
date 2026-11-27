package dev.itayp.tasker.channel

/**
 * The attachment policy every chat channel shares (`docs/MESSAGING-CHANNELS.md`, section 4): which
 * files capture reads, how large they may be, and the audio format label the transcription model
 * expects. Pure functions; each channel does its own downloading, and never writes bytes to disk.
 */
object AttachmentIntake {

    /**
     * Caps on what we'll pull down and base64 into a prompt. Chat apps compress photos hard (a
     * phone snapshot lands well under a megabyte), and a voice note is a few hundred KB per minute,
     * so these are guardrails against a pathological file, not everyday limits. Base64 inflates
     * the payload by a third on top of these.
     */
    const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    const val MAX_AUDIO_BYTES = 5 * 1024 * 1024

    /** PDFs and text files, as on inbound email. A text file is cut to [TextAttachments.MAX_CHARS] after. */
    const val MAX_DOCUMENT_BYTES = 5 * 1024 * 1024

    const val PDF = "application/pdf"

    /** Why an attachment was turned down. */
    enum class Rejection { TOO_LARGE, UNSUPPORTED_TYPE, DOWNLOAD_FAILED }

    /** What a file will be read as: its kind, the media type to send it under, and an audio format. */
    data class Intake(val kind: AttachmentKind, val mediaType: String, val format: String? = null)

    fun limitFor(kind: AttachmentKind): Int = when (kind) {
        AttachmentKind.IMAGE -> MAX_IMAGE_BYTES
        AttachmentKind.AUDIO -> MAX_AUDIO_BYTES
        AttachmentKind.DOCUMENT, AttachmentKind.TEXT -> MAX_DOCUMENT_BYTES
    }

    /**
     * How a file declared as [declaredType] and named [filename] is read, or null when capture
     * doesn't read it: an image, a PDF, a text file or calendar invitation, or audio in a format
     * the transcriber takes.
     */
    fun classify(declaredType: String?, filename: String?): Intake? {
        val mediaType = normalize(declaredType)
        val textType = TextAttachments.mediaTypeOf(mediaType, filename)
        return when {
            mediaType.startsWith("image/") -> Intake(AttachmentKind.IMAGE, mediaType)
            mediaType == PDF || filename?.lowercase()?.endsWith(".pdf") == true -> Intake(AttachmentKind.DOCUMENT, PDF)
            textType != null -> Intake(AttachmentKind.TEXT, textType)
            mediaType.startsWith("audio/") -> audioFormat(mediaType)?.let { Intake(AttachmentKind.AUDIO, mediaType, it) }
            else -> null
        }
    }

    /**
     * The attachment for downloaded [bytes], or null when there is nothing to read in them: empty,
     * or binary under a text name.
     */
    fun attachment(intake: Intake, bytes: ByteArray, filename: String?): InboundAttachment? = when {
        bytes.isEmpty() -> null
        intake.kind == AttachmentKind.TEXT -> TextAttachments.of(bytes, intake.mediaType, filename)
        else -> InboundAttachment(intake.kind, bytes, intake.mediaType, intake.format, filename)
    }

    /**
     * The codec label the transcription endpoint wants, from a declared audio media type. Voice
     * notes are OGG/Opus on both Telegram and WhatsApp; the rest cover audio files a user might
     * forward. An unmapped type is declined rather than guessed: a wrong label is a provider error.
     */
    fun audioFormat(mediaType: String?): String? = when (normalize(mediaType)) {
        "audio/ogg", "audio/opus", "audio/x-opus+ogg" -> "ogg"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/aac" -> "aac"
        else -> null
    }

    /** Lowercase, without parameters: `audio/ogg; codecs=opus` → `audio/ogg`. */
    fun normalize(mediaType: String?): String = mediaType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
}
