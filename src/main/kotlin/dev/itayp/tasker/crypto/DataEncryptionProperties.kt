package dev.itayp.tasker.crypto

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.Base64

/**
 * KEK material loaded from `tasker.encryption.kek` (base64-encoded 32 bytes).
 *
 * The raw KEK bytes are derived lazily and cached. Validation deliberately runs on
 * first access (typically at startup, when [UserCryptoService] resolves the bean
 * graph), so a malformed key fails the boot rather than only the first encrypt call.
 */
@ConfigurationProperties("tasker.encryption")
data class DataEncryptionProperties(
    val kek: String,
) {
    val kekBytes: ByteArray by lazy {
        val decoded = try {
            Base64.getDecoder().decode(kek)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("tasker.encryption.kek is not valid base64", e)
        }
        require(decoded.size == 32) {
            "tasker.encryption.kek must decode to exactly 32 bytes (got ${decoded.size})"
        }
        decoded
    }
}
