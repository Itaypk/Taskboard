package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "one_off_event")
class OneOffEventEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    @Column(name = "user_id")
    var userId: UUID? = null

    @Column(name = "board_id")
    var boardId: UUID? = null

    @Column
    var title: ByteArray? = null

    @Column
    var location: ByteArray? = null

    @Column
    var notes: ByteArray? = null

    @Column(name = "starts_at")
    var startsAt: Instant? = null

    @Column(name = "ends_at")
    var endsAt: Instant? = null

    @Column(name = "ical_uid")
    var icalUid: String? = null

    @Column(name = "created_at")
    var createdAt: Instant? = null

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null
}
