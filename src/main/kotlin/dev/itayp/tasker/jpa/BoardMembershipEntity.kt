package dev.itayp.tasker.jpa

import dev.itayp.tasker.model.BoardRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Links a user to a board with a role. The `(board_id, user_id)` pair is unique — a user appears
 * at most once per board. This is the authorization key for board-owned content: access is granted
 * by membership rather than by the implicit user-id ownership the app used before boards existed.
 */
@Entity
@Table(name = "board_membership")
class BoardMembershipEntity {
    @Id
    var id: UUID? = null

    @Column(name = "board_id", nullable = false)
    var boardId: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    var role: BoardRole? = null

    @Column(name = "joined_at", nullable = false)
    var joinedAt: Instant? = null
}
