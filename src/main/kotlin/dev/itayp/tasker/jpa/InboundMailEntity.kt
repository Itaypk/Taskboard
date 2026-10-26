package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** An inbound email waiting to become tasks, or recently processed. See changeset 018. */
@Entity
@Table(name = "inbound_mail")
class InboundMailEntity {
    @Id
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Column(name = "dedup_key", nullable = false, length = 64)
    var dedupKey: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: InboundMailStatus = InboundMailStatus.PENDING

    @Column(nullable = false)
    var attempts: Int = 0

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant? = null

    /** The parsed message as JSON, encrypted under the user's DEK. Null once processed. */
    @Column
    var payload: ByteArray? = null

    /**
     * The images and PDFs to capture from, as JSON, encrypted under the user's DEK. Null when there
     * are none, and once processed. See changeset 019.
     */
    @Column
    var attachments: ByteArray? = null

    @Column(name = "received_at", nullable = false)
    var receivedAt: Instant? = null

    @Column(name = "processed_at")
    var processedAt: Instant? = null
}

enum class InboundMailStatus { PENDING, DONE, FAILED }
