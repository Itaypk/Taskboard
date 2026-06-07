package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A board owns tasks, categories, and tags. A user belongs to one or more boards via
 * [BoardMembershipEntity]; a board with a single member is effectively private. The id is
 * assigned by the service layer (so the board's DEK and membership can be created in the same
 * unit of work). [name] is encrypted under the board's DEK — see `BoardCryptoService`.
 */
@Entity
@Table(name = "board")
class BoardEntity {
    @Id
    var id: UUID? = null

    @Column
    var name: ByteArray? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null
}
