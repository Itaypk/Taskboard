package dev.itayp.tasker.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Secrets that act as capabilities (magic-link logins, board invitations, email verification, API
 * tokens): the plaintext goes to the user once, and only its SHA-256 hex digest is stored. A leaked
 * database dump or read replica then yields no usable token.
 *
 * SHA-256 without a salt or key-stretching is deliberate: the secrets are 256 bits of
 * [SecureRandom] output, so there is nothing to brute-force, and lookup by digest stays a plain
 * indexed equality query.
 */
object CapabilityTokens {

    /** 32 bytes = 256 bits, base64url-encoded to 43 characters. */
    private const val SECRET_BYTES = 32

    private val random = SecureRandom()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** A fresh random secret, safe to embed in a URL. */
    fun generate(): String {
        val bytes = ByteArray(SECRET_BYTES)
        random.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }

    /** The value to store and look up by: lowercase hex SHA-256 (64 chars) of [token]. */
    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
