package dev.itayp.tasker.crypto

import org.springframework.stereotype.Component
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Lookup key for inbound email address tokens: HMAC-SHA256 under a key derived from the KEK.
 *
 * The other capability secrets are hashed with plain SHA-256 (`CapabilityTokens`), which is safe
 * only because they carry 256 bits. An address token has to stay short enough to type into a To
 * field (10 base32 characters, ~50 bits), and 2^50 SHA-256 guesses against a leaked dump is about
 * a day on one GPU. Keying the hash with a secret that never sits in the database makes a dump on
 * its own useless.
 *
 * The derived key is `HMAC(KEK, label)`, so it is distinct from every other use of the KEK. If the
 * KEK is ever rotated, the stored HMACs must be recomputed from the encrypted tokens.
 */
@Component
class InboundMailTokenHasher(properties: DataEncryptionProperties) {

    private val key: SecretKeySpec by lazy {
        SecretKeySpec(hmac(SecretKeySpec(properties.kekBytes, ALGORITHM), KEY_LABEL), ALGORITHM)
    }

    /** Lowercase hex HMAC-SHA256 (64 chars) of [token]. */
    fun hash(token: String): String =
        hmac(key, token.lowercase()).joinToString("") { "%02x".format(it) }

    private fun hmac(key: SecretKeySpec, input: String): ByteArray =
        Mac.getInstance(ALGORITHM).apply { init(key) }.doFinal(input.toByteArray(Charsets.UTF_8))

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val KEY_LABEL = "tasker-inbound-mail-token-v1"
    }
}
