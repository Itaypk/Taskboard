package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A user's personal inbound email address, `tasks.<token>@<inbound domain>`. At most one per user;
 * see `InboundMailAddressService` and changeset 017 for why the token is stored both as an HMAC
 * (the lookup key) and encrypted (so the address can be shown again).
 */
@Entity
@Table(name = "inbound_mail_address")
class InboundMailAddressEntity {
    @Id
    @Column(name = "user_id")
    var userId: UUID? = null

    /** HMAC-SHA256 hex of the token (`InboundMailTokenHasher`). Never the secret itself. */
    @Column(name = "token_hmac", nullable = false, length = 64)
    var tokenHmac: String? = null

    /** The token, encrypted under the user's DEK. */
    @Column(nullable = false)
    var token: ByteArray? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null
}
