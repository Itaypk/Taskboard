package dev.itayp.tasker.channel.whatsapp

import dev.itayp.tasker.channel.WebhookSignatures
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * The WhatsApp Cloud API webhook: the subscription handshake, and signed deliveries. Each delivery
 * is summarized in the log (ids and types only); when quick-add is configured ([WhatsAppMessaging]
 * exists) its messages then go on to capture. Design: `docs/MESSAGING-CHANNELS.md`, section 2.
 *
 * Reached through the stateless `webhookFilterChain`; the signature is the authentication.
 *
 * Meta redelivers anything that isn't a 200 for up to 7 days, so once a delivery is proven to be
 * Meta's it is always acknowledged, even when we can't make sense of it.
 */
@RestController
@RequestMapping(WhatsAppWebhookController.PATH)
class WhatsAppWebhookController(
    private val properties: WhatsAppProperties,
    private val objectMapper: ObjectMapper,
    private val messaging: ObjectProvider<WhatsAppMessaging>,
) {
    private val log = LoggerFactory.getLogger(WhatsAppWebhookController::class.java)

    /**
     * Meta's one-off check when the callback URL is saved: echo `hub.challenge` if the token is
     * the one typed into the app's webhook settings.
     */
    @GetMapping(produces = [MediaType.TEXT_PLAIN_VALUE])
    fun verify(
        @RequestParam(name = "hub.mode", required = false) mode: String?,
        @RequestParam(name = "hub.verify_token", required = false) verifyToken: String?,
        @RequestParam(name = "hub.challenge", required = false) challenge: String?,
    ): ResponseEntity<String> {
        if (!properties.active) return ResponseEntity.notFound().build()
        val tokenMatches = verifyToken != null &&
            MessageDigest.isEqual(verifyToken.toByteArray(), properties.verifyToken.toByteArray())
        if (mode != "subscribe" || !tokenMatches || challenge.isNullOrEmpty()) {
            log.warn("WhatsApp webhook verification refused (mode={})", mode)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        log.info("WhatsApp webhook verified")
        return ResponseEntity.ok(challenge)
    }

    @PostMapping
    fun receive(
        @RequestHeader(name = SIGNATURE_HEADER, required = false) signature: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        if (!properties.active) return ResponseEntity.notFound().build()
        if (request.contentLengthLong > MAX_BODY_BYTES) return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build()
        // Content-Length can be absent (chunked), so cap the read itself too.
        val body = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build()

        val expected = WebhookSignatures.hmacHex("HmacSHA256", properties.appSecret, body)
        if (!WebhookSignatures.matches(expected, signature?.removePrefix(SIGNATURE_PREFIX))) {
            log.warn("WhatsApp webhook delivery with a missing or wrong signature ({} bytes), ignored", body.size)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        if (properties.logPayloads) {
            log.info("WhatsApp webhook raw payload (TASKER_WHATSAPP_LOG_PAYLOADS is on): {}", String(body, Charsets.UTF_8))
        }
        val payload = try {
            objectMapper.readTree(body)
        } catch (e: JacksonException) {
            // Signed, so Meta's: acknowledge anyway, or it would redeliver for a week.
            log.warn("WhatsApp webhook delivery is not valid JSON ({} bytes): {}", body.size, e.originalMessage)
            return ResponseEntity.ok().build()
        }
        log.info("WhatsApp webhook delivery: {}", WhatsAppWebhookSummary.describe(payload, properties.phoneNumberId))
        try {
            messaging.ifAvailable { it.deliver(payload) }
        } catch (e: Exception) {
            // Still a 200: a redelivery would fail the same way, for a week.
            log.error("WhatsApp webhook delivery could not be handed to capture", e)
        }
        return ResponseEntity.ok().build()
    }

    companion object {
        const val PATH = "/webhooks/whatsapp"
        const val SIGNATURE_HEADER = "X-Hub-Signature-256"
        private const val SIGNATURE_PREFIX = "sha256="

        /** Meta's documented maximum payload size. */
        const val MAX_BODY_BYTES = 3 * 1024 * 1024
    }
}
