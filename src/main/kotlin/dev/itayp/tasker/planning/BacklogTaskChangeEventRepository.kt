package dev.itayp.tasker.planning

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface BacklogTaskChangeEventRepository : JpaRepository<BacklogTaskChangeEventEntity, UUID> {

    /** The planner's cross-board "since last session" diff: events on any of the user's boards. */
    fun findAllByBoardIdInAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(
        boardIds: Collection<UUID>,
        occurredAt: Instant,
    ): List<BacklogTaskChangeEventEntity>

    /** Personal stats: everything this user did, across boards (scoping is by actor, not board). */
    fun findAllByActorUserIdOrderByOccurredAtAsc(actorUserId: UUID): List<BacklogTaskChangeEventEntity>
}
