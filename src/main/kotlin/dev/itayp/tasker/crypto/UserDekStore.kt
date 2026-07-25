package dev.itayp.tasker.crypto

import dev.itayp.nescioquid.crypto.DekStore
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * App-side [DekStore] over `user_data_key` (JPA). This is the persistence adapter that keeps
 * [EnvelopeCipher] free of any JPA dependency; it owns the persistence-only columns
 * (`kek_version`, `created_at`). Not a Spring bean — [UserCryptoService] constructs it.
 */
class UserDekStore(
    private val repository: UserDataKeyRepository,
    private val clock: Clock,
) : DekStore {

    override fun exists(id: UUID): Boolean = repository.existsById(id)

    override fun findWrappedDek(id: UUID): ByteArray? =
        repository.findById(id).map { it.wrappedDek }.orElse(null)

    override fun saveWrappedDek(id: UUID, wrappedDek: ByteArray) {
        repository.save(UserDataKeyEntity().apply {
            this.userId = id
            this.wrappedDek = wrappedDek
            this.kekVersion = 1
            this.createdAt = Instant.now(clock)
        })
    }
}
