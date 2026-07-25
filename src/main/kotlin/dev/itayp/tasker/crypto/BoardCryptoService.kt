package dev.itayp.tasker.crypto

import dev.itayp.nescioquid.crypto.EnvelopeCipher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Per-board envelope encryption — the board-scoped twin of [UserCryptoService]. A thin,
 * app-wired delegate over the reusable [EnvelopeCipher]: it owns only the Spring wiring and
 * board-scoped naming; the DEK lifecycle, caching, and AES-GCM live in [EnvelopeCipher], with
 * `board_data_key` storage behind [BoardDekStore].
 *
 * Board-owned plaintext (task title/description, change-event title snapshots, the board name)
 * is encrypted with AES-256-GCM, the board's UUID bytes bound as AAD so a ciphertext from one
 * board cannot be decrypted in another board's context. Personal fields stay under
 * [UserCryptoService]; only genuinely shared, board-owned content lives here.
 */
@Service
class BoardCryptoService(
    boardDataKeyRepository: BoardDataKeyRepository,
    properties: DataEncryptionProperties,
    clock: Clock,
) {
    // KEK resolved lazily inside EnvelopeCipher; no system-AAD path is used board-side, so the
    // board id AAD (16 bytes) is the only binding in play here.
    private val cipher = EnvelopeCipher(
        kekProvider = { properties.kekBytes },
        dekStore = BoardDekStore(boardDataKeyRepository, clock),
        systemAad = SYSTEM_AAD,
    )

    /**
     * Creates and stores a wrapped DEK for [boardId]. Called once on board creation.
     * Idempotent: if a row already exists, this is a no-op.
     */
    @Transactional
    fun ensureBoardKey(boardId: UUID) = cipher.ensureKey(boardId)

    fun encrypt(boardId: UUID, plaintext: String?): ByteArray? = cipher.encrypt(boardId, plaintext)

    fun decrypt(boardId: UUID, ciphertext: ByteArray?): String? = cipher.decrypt(boardId, ciphertext)

    private companion object {
        // Board crypto never uses the system path; a distinct constant keeps intent clear.
        private val SYSTEM_AAD = "tasker-system-board".toByteArray(Charsets.UTF_8)
    }
}
