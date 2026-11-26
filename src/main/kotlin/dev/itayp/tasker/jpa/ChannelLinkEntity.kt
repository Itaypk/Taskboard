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

/**
 * A chat account linked to a user on a messaging channel (changeset 022, `ChannelLinkService`).
 * Not a login method: it never appears among the user's auth identities.
 */
@Entity
@Table(name = "channel_link")
class ChannelLinkEntity {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var channel: ChannelType? = null

    /** `ChannelLinkHasher` HMAC of the chat account's id: the lookup key. */
    @Column(name = "external_id_hmac", nullable = false, length = 64)
    var externalIdHmac: String? = null

    /** The chat account's id, encrypted under the user's DEK. */
    @Column(name = "external_id", nullable = false)
    var externalId: ByteArray? = null

    /** What Settings shows for the account (a phone number), encrypted under the user's DEK. */
    @Column
    var display: ByteArray? = null

    @Column(name = "linked_at", nullable = false)
    var linkedAt: Instant? = null
}
