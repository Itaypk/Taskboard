package dev.itayp.tasker.crypto

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Per-user envelope encryption. A thin, app-wired delegate over the reusable [EnvelopeCipher]:
 * this class owns only the Spring wiring (bean, transaction boundary) and the user-scoped
 * naming; the actual DEK lifecycle, caching, and AES-GCM live in [EnvelopeCipher], with
 * `user_data_key` storage behind [UserDekStore].
 *
 * Each user has a DEK (random 32 bytes) wrapped under the app-wide KEK. Plaintext is encrypted
 * with AES-256-GCM, the user's UUID bytes bound as AAD so a ciphertext from one user's row
 * cannot be decrypted in another user's context.
 */
@Service
class UserCryptoService(
    userDataKeyRepository: UserDataKeyRepository,
    properties: DataEncryptionProperties,
    clock: Clock,
) {
    // KEK is resolved lazily inside EnvelopeCipher (via the provider below), so a malformed/
    // mis-sized KEK still fails on first use rather than at construction. SYSTEM_AAD is pinned
    // to its historical value so magic-link tokens encrypted before this refactor still decrypt.
    private val cipher = EnvelopeCipher(
        kekProvider = { properties.kekBytes },
        dekStore = UserDekStore(userDataKeyRepository, clock),
        systemAad = SYSTEM_AAD,
    )

    /**
     * Creates and stores a wrapped DEK for [userId]. Called once on user registration.
     * Idempotent: if a row already exists, this is a no-op.
     */
    @Transactional
    fun ensureUserKey(userId: UUID) = cipher.ensureKey(userId)

    fun encrypt(userId: UUID, plaintext: String?): ByteArray? = cipher.encrypt(userId, plaintext)

    fun decrypt(userId: UUID, ciphertext: ByteArray?): String? = cipher.decrypt(userId, ciphertext)

    /**
     * Encrypts directly under the app KEK, for data that isn't owned by a user yet —
     * e.g. the email held in a pending magic-link login token before any account exists.
     */
    fun encryptSystem(plaintext: String?): ByteArray? = cipher.encryptSystem(plaintext)

    fun decryptSystem(ciphertext: ByteArray?): String? = cipher.decryptSystem(ciphertext)

    private companion object {
        // Fixed AAD for KEK-level (non-user) envelopes; distinct from any 16-byte user-id AAD.
        private val SYSTEM_AAD = "tasker-system".toByteArray(Charsets.UTF_8)
    }
}
