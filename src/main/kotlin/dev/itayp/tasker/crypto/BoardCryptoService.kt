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
 * Per-board envelope encryption — the board-scoped twin of [UserCryptoService].
 *
 * Each board has a DEK (random 32 bytes) wrapped under the app-wide KEK and stored in
 * `board_data_key`. On first access in this process the DEK is unwrapped and cached; later calls
 * hit the cache. Board-owned plaintext (task title/description, change-event title snapshots, the
 * board name) is encrypted with AES-256-GCM, binding the board's UUID bytes as AAD so a ciphertext
 * from one board cannot be decrypted in another board's context.
 *
 * Personal fields (profile, settings, planned-task title/notes) stay under [UserCryptoService];
 * only genuinely shared, board-owned content lives here.
 */
@Service
class BoardCryptoService(
    private val boardDataKeyRepository: BoardDataKeyRepository,
    private val properties: DataEncryptionProperties,
    private val clock: Clock,
) {
    private val dekCache = ConcurrentHashMap<UUID, ByteArray>()
    private val random = SecureRandom()

    /**
     * Creates and stores a wrapped DEK for [boardId]. Called once on board creation.
     * Idempotent: if a row already exists, this is a no-op.
     */
    @Transactional
    fun ensureBoardKey(boardId: UUID) {
        if (boardDataKeyRepository.existsById(boardId)) return
        val dek = ByteArray(32).also(random::nextBytes)
        val wrapped = AesGcmCipher.seal(properties.kekBytes, dek, boardIdAad(boardId))
        boardDataKeyRepository.save(BoardDataKeyEntity().apply {
            this.boardId = boardId
            this.wrappedDek = wrapped
            this.kekVersion = 1
            this.createdAt = Instant.now(clock)
        })
        dekCache[boardId] = dek
    }

    fun encrypt(boardId: UUID, plaintext: String?): ByteArray? {
        if (plaintext == null) return null
        return AesGcmCipher.seal(dekFor(boardId), plaintext.toByteArray(Charsets.UTF_8), boardIdAad(boardId))
    }

    fun decrypt(boardId: UUID, ciphertext: ByteArray?): String? {
        if (ciphertext == null) return null
        return String(AesGcmCipher.open(dekFor(boardId), ciphertext, boardIdAad(boardId)), Charsets.UTF_8)
    }

    private fun dekFor(boardId: UUID): ByteArray =
        dekCache.computeIfAbsent(boardId) { id ->
            val row = boardDataKeyRepository.findById(id).orElseThrow {
                IllegalStateException("No data key for board $id; ensureBoardKey must be called on board creation")
            }
            AesGcmCipher.open(properties.kekBytes, row.wrappedDek!!, boardIdAad(id))
        }

    private fun boardIdAad(boardId: UUID): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(boardId.mostSignificantBits)
            .putLong(boardId.leastSignificantBits)
            .array()
}
