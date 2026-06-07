package dev.itayp.tasker.crypto

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Per-board wrapped DEK. Mirrors [UserDataKeyEntity] but keyed by board, so board-owned content
 * (task title/description, change-event title snapshots, board name) can be read by every member
 * of the board regardless of who authored it. See [BoardCryptoService].
 */
@Entity
@Table(name = "board_data_key")
class BoardDataKeyEntity {
    @Id
    @Column(name = "board_id")
    var boardId: UUID? = null

    @Column(name = "wrapped_dek", nullable = false)
    var wrappedDek: ByteArray? = null

    @Column(name = "kek_version", nullable = false)
    var kekVersion: Short = 1

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null
}
