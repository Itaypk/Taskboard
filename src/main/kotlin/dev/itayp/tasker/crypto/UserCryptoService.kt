package dev.itayp.tasker.crypto

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-user envelope encryption.
 *
 * Each user has a DEK (random 32 bytes) wrapped under the app-wide KEK and stored
 * in `user_data_key`. On first access for a user in this process, the DEK is
 * unwrapped and cached in memory; subsequent calls hit the cache. Plaintext values
 * are encrypted with AES-256-GCM, with the user's UUID bytes bound as AAD so a
 * ciphertext from one user's row cannot be decrypted in another user's context.
 */
@Service
class UserCryptoService(
    private val userDataKeyRepository: UserDataKeyRepository,
    private val properties: DataEncryptionProperties,
    private val clock: Clock,
) {
    private val dekCache = ConcurrentHashMap<UUID, ByteArray>()
    private val random = SecureRandom()

    /**
     * Creates and stores a wrapped DEK for [userId]. Called once on user registration.
     * Idempotent: if a row already exists, this is a no-op.
     */
    @Transactional
    fun ensureUserKey(userId: UUID) {
        if (userDataKeyRepository.existsById(userId)) return
        val dek = ByteArray(32).also(random::nextBytes)
        val wrapped = AesGcmCipher.seal(properties.kekBytes, dek, userIdAad(userId))
        userDataKeyRepository.save(UserDataKeyEntity().apply {
            this.userId = userId
            this.wrappedDek = wrapped
            this.kekVersion = 1
            this.createdAt = Instant.now(clock)
        })
        dekCache[userId] = dek
    }

    fun encrypt(userId: UUID, plaintext: String?): ByteArray? {
        if (plaintext == null) return null
        return AesGcmCipher.seal(dekFor(userId), plaintext.toByteArray(Charsets.UTF_8), userIdAad(userId))
    }

    fun decrypt(userId: UUID, ciphertext: ByteArray?): String? {
        if (ciphertext == null) return null
        return String(AesGcmCipher.open(dekFor(userId), ciphertext, userIdAad(userId)), Charsets.UTF_8)
    }

    /**
     * Encrypts directly under the app KEK, for data that isn't owned by a user yet —
     * e.g. the email held in a pending magic-link login token before any account exists.
     * Bound to a fixed AAD so these envelopes can't be swapped in for per-user ciphertext.
     */
    fun encryptSystem(plaintext: String?): ByteArray? {
        if (plaintext == null) return null
        return AesGcmCipher.seal(properties.kekBytes, plaintext.toByteArray(Charsets.UTF_8), SYSTEM_AAD)
    }

    fun decryptSystem(ciphertext: ByteArray?): String? {
        if (ciphertext == null) return null
        return String(AesGcmCipher.open(properties.kekBytes, ciphertext, SYSTEM_AAD), Charsets.UTF_8)
    }

    private fun dekFor(userId: UUID): ByteArray =
        dekCache.computeIfAbsent(userId) { id ->
            val row = userDataKeyRepository.findById(id).orElseThrow {
                IllegalStateException("No data key for user $id; ensureUserKey must be called on registration")
            }
            AesGcmCipher.open(properties.kekBytes, row.wrappedDek!!, userIdAad(id))
        }

    private fun userIdAad(userId: UUID): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(userId.mostSignificantBits)
            .putLong(userId.leastSignificantBits)
            .array()

    private companion object {
        // Fixed AAD for KEK-level (non-user) envelopes; distinct from any 16-byte user-id AAD.
        private val SYSTEM_AAD = "tasker-system".toByteArray(Charsets.UTF_8)
    }
}
