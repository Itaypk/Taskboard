package dev.itayp.tasker.channel.whatsapp

import dev.itayp.tasker.channel.AttachmentIntake
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.messaging.MediaFetch
import org.slf4j.LoggerFactory

/**
 * Downloads a photo, voice note or file from a webhook message (`docs/MESSAGING-CHANNELS.md`,
 * section 4): two calls, the media id's metadata and then its short-lived URL, both with the access
 * token. Declines what capture doesn't read before downloading, checks the declared size first and
 * the actual size while reading, and keeps the bytes in memory only.
 */
class WhatsAppMediaFetcher(private val client: WhatsAppClient) {
    private val log = LoggerFactory.getLogger(WhatsAppMediaFetcher::class.java)

    fun fetch(ref: WhatsAppMediaRef): MediaFetch {
        // The webhook's own type, so an unreadable file costs no API call.
        val declared = AttachmentIntake.classify(ref.mimeType, ref.filename)
            ?: return rejected(AttachmentIntake.Rejection.UNSUPPORTED_TYPE, "unsupported media type ${AttachmentIntake.normalize(ref.mimeType)}")
        val limit = AttachmentIntake.limitFor(declared.kind)
        val bytes = try {
            val info = client.mediaInfo(ref.mediaId)
            if (info.fileSize != null && info.fileSize > limit) {
                return rejected(AttachmentIntake.Rejection.TOO_LARGE, "${info.fileSize}B exceeds the ${limit}B limit")
            }
            client.download(info.url, limit)
        } catch (e: WhatsAppApiException) {
            return rejected(AttachmentIntake.Rejection.DOWNLOAD_FAILED, "download failed (HTTP ${e.status}, code ${e.code})")
        }
        if (bytes.size > limit) return rejected(AttachmentIntake.Rejection.TOO_LARGE, "stream exceeded the ${limit}B limit")
        if (bytes.isEmpty()) return rejected(AttachmentIntake.Rejection.DOWNLOAD_FAILED, "empty download")
        val attachment = AttachmentIntake.attachment(declared, bytes, ref.filename)
            ?: return rejected(AttachmentIntake.Rejection.UNSUPPORTED_TYPE, "no text in a ${declared.mediaType} file")
        log.debug("Downloaded WhatsApp {} attachment ({}, {}B) for capture", declared.kind, declared.mediaType, bytes.size)
        return MediaFetch.Fetched(ChannelInbound.Media(listOf(attachment), ref.caption))
    }

    private fun rejected(reason: AttachmentIntake.Rejection, why: String): MediaFetch {
        log.info("Declining a WhatsApp attachment: {}", why)
        return MediaFetch.Rejected(reason)
    }
}
