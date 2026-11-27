package dev.itayp.tasker.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Lookup key for a low-entropy value: HMAC-SHA256 under a key derived from the KEK.
 *
 * The capability secrets are hashed with plain SHA-256 (`CapabilityTokens`), which is safe only
 * because they carry 256 bits. A short code, or an identifier such as a phone number, has far
 * less, so a plain hash in a leaked dump could be brute-forced offline. Keying the hash with a
 * secret that never sits in the database makes a dump on its own useless.
 *
 * The derived key is `HMAC(KEK, keyLabel)`, so each subclass's [keyLabel] gives it a key distinct
 * from every other use of the KEK. Input is lowercased first. If the KEK is ever rotated, the
 * stored HMACs must be recomputed from the encrypted originals.
 */
abstract class KekDerivedHasher(properties: DataEncryptionProperties, private val keyLabel: String) {

    private val key: SecretKeySpec by lazy {
        SecretKeySpec(hmac(SecretKeySpec(properties.kekBytes, ALGORITHM), keyLabel), ALGORITHM)
    }

    /** Lowercase hex HMAC-SHA256 (64 chars) of [value]. */
    fun hash(value: String): String =
        hmac(key, value.lowercase()).joinToString("") { "%02x".format(it) }

    private fun hmac(key: SecretKeySpec, input: String): ByteArray =
        Mac.getInstance(ALGORITHM).apply { init(key) }.doFinal(input.toByteArray(Charsets.UTF_8))

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
