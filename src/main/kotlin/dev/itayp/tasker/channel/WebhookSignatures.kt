package dev.itayp.tasker.channel

import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The one thing inbound chat webhooks have in common: an HMAC over the raw body, sent as hex.
 * Deliberately no verifier abstraction beyond this (`docs/MESSAGING-CHANNELS.md`, section 2):
 * other providers add timestamps, JWTs or Ed25519, and each controller checks its own header.
 */
object WebhookSignatures {

    /** Lowercase hex of the HMAC of [body] under [secret], e.g. [algorithm] `HmacSHA256`. */
    fun hmacHex(algorithm: String, secret: String, body: ByteArray): String {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), algorithm))
        return HexFormat.of().formatHex(mac.doFinal(body))
    }

    /** Constant-time comparison, so a forger can't learn the expected value byte by byte. */
    fun matches(expectedHex: String, providedHex: String?): Boolean =
        providedHex != null &&
            MessageDigest.isEqual(expectedHex.toByteArray(Charsets.US_ASCII), providedHex.lowercase().toByteArray(Charsets.US_ASCII))
}
