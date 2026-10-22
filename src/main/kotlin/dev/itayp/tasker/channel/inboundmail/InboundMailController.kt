package dev.itayp.tasker.channel.inboundmail

import dev.itayp.tasker.config.InboundMailProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

/**
 * Where the MTA hands over each inbound email: `POST /internal/inbound-mail`, the raw RFC 822
 * message as the body, one envelope recipient per request (`docs/INBOUND-EMAIL.md`).
 *
 * The MTA reads the status: 2xx and 4xx both end the delivery (4xx = dropped on purpose, e.g. an
 * unknown address: answering the sender would only be backscatter), anything else makes it keep
 * the message and retry. So "can't take it right now" must never be a 4xx: the feature being off
 * is 503, and a wrong delivery token (a configuration error, not an attack, on a loopback-only
 * path) is 401, which the delivery script also treats as retry.
 */
@RestController
class InboundMailController(
    private val receiver: InboundMailReceiver,
    private val properties: InboundMailProperties,
) {

    @PostMapping("/internal/inbound-mail")
    fun receive(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
        @RequestHeader(name = "X-Envelope-From", required = false) envelopeFrom: String?,
        @RequestHeader(name = "X-Envelope-To") envelopeTo: String,
        @RequestBody raw: ByteArray,
    ): ResponseEntity<Void> {
        if (!properties.active) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        if (!tokenMatches(token)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val status = when (receiver.receive(envelopeFrom?.unbracket(), envelopeTo.unbracket(), raw)) {
            ReceiveOutcome.UNKNOWN_RECIPIENT -> HttpStatus.NOT_FOUND
            ReceiveOutcome.MALFORMED -> HttpStatus.BAD_REQUEST
            else -> HttpStatus.ACCEPTED
        }
        return ResponseEntity.status(status).build()
    }

    private fun tokenMatches(token: String?): Boolean =
        token != null && MessageDigest.isEqual(token.toByteArray(), properties.deliveryToken.toByteArray())

    /** `<a@b>` → `a@b`; `<>` (a bounce's null sender) → empty. */
    private fun String.unbracket(): String = trim().removePrefix("<").removeSuffix(">").trim()

    companion object {
        const val TOKEN_HEADER = "X-Inbound-Mail-Token"
    }
}
