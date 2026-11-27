package dev.itayp.tasker.jpa

import dev.itayp.tasker.channel.ChannelType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UuidGenerator
import java.time.Instant
import java.util.UUID

/** A pending link code (changeset 022, `ChannelLinkService`): single use, short-lived. */
@Entity
@Table(name = "channel_link_token")
class ChannelLinkTokenEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var channel: ChannelType? = null

    /** `ChannelLinkHasher` HMAC of the code. Never the code itself. */
    @Column(name = "token_hmac", nullable = false, length = 64)
    var tokenHmac: String? = null

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null
}
