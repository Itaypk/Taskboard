package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A user's secret calendar subscription feed, `/calendar/<token>.ics`. At most one per user; see
 * `CalendarFeedService` and changeset 020 for why the token is stored both hashed and encrypted.
 */
@Entity
@Table(name = "calendar_feed")
class CalendarFeedEntity {
    @Id
    @Column(name = "user_id")
    var userId: UUID? = null

    /** `CapabilityTokens.hash` of the token: the lookup key. Never the secret itself. */
    @Column(name = "token_hash", nullable = false, length = 64)
    var tokenHash: String? = null

    /** The token, encrypted under the user's DEK, so Settings can show the link again. */
    @Column(nullable = false)
    var token: ByteArray? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    /** When a calendar app last fetched the feed; stamped at most every few minutes. */
    @Column(name = "last_fetched_at")
    var lastFetchedAt: Instant? = null
}
