package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A calendar the planner reads: one of a user's private ICS addresses (GitHub issue #347). See
 * `CalendarSourceService` and changeset 022.
 */
@Entity
@Table(name = "calendar_source")
class CalendarSourceEntity {
    @Id
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    /** The address, encrypted under the user's DEK. It is the secret: never log or return it. */
    @Column(nullable = false)
    var url: ByteArray? = null

    /** The user's name for the calendar, encrypted under the user's DEK. */
    @Column(nullable = false)
    var label: ByteArray? = null

    /** `TITLES` or `BUSY`: how much of each event the assistant sees ([dev.itayp.tasker.calendar.source.CalendarSourceDetail]). */
    @Column(nullable = false, length = 16)
    var detail: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "last_attempt_at")
    var lastAttemptAt: Instant? = null

    @Column(name = "last_success_at")
    var lastSuccessAt: Instant? = null

    /** An `IcsFetchError` name when the last read failed; null when it worked. */
    @Column(name = "last_error", length = 32)
    var lastError: String? = null

    /** Events in the seven days after the last successful read. */
    @Column(name = "last_event_count")
    var lastEventCount: Int? = null
}
