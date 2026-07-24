package dev.itayp.tasker.crypto

import dev.itayp.nescioquid.crypto.DekStore
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * App-side [DekStore] over `board_data_key` (JPA) — the board-scoped twin of [UserDekStore].
 * Keeps [EnvelopeCipher] free of any JPA dependency and owns the persistence-only columns
 * (`kek_version`, `created_at`). Not a Spring bean — [BoardCryptoService] constructs it.
 */
class BoardDekStore(
    private val repository: BoardDataKeyRepository,
    private val clock: Clock,
) : DekStore {

    override fun exists(id: UUID): Boolean = repository.existsById(id)

    override fun findWrappedDek(id: UUID): ByteArray? =
        repository.findById(id).map { it.wrappedDek }.orElse(null)

    override fun saveWrappedDek(id: UUID, wrappedDek: ByteArray) {
        repository.save(BoardDataKeyEntity().apply {
            this.boardId = id
            this.wrappedDek = wrappedDek
            this.kekVersion = 1
            this.createdAt = Instant.now(clock)
        })
    }
}
