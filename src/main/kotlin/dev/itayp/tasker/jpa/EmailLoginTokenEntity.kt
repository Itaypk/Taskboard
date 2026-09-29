package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A pending passwordless magic-link login. Created when an email is submitted on the
 * login page and consumed when the link is clicked. There is deliberately no user FK:
 * a token may be the first thing that ever exists for a brand-new account.
 *
 * The email is stored encrypted under the app KEK (no user DEK exists yet); `emailHash`
 * is the non-secret handle used for rate-limiting and matching to an existing account.
 */
@Entity
@Table(name = "email_login_token")
class EmailLoginTokenEntity {
    /** SHA-256 hex of the emailed secret (see [dev.itayp.tasker.util.CapabilityTokens]); the plaintext is never stored. */
    @Id
    @Column(name = "token", length = 64)
    var tokenHash: String? = null

    @Column(name = "email_hash", nullable = false, length = 64)
    var emailHash: String? = null

    /** Email plaintext encrypted under the app KEK; see UserCryptoService.encryptSystem. */
    @Column(name = "email_enc", nullable = false)
    var emailEnc: ByteArray? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null
}
