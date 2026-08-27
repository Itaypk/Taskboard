package dev.itayp.tasker.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A long-lived personal API token, used to authenticate an external caller (an AI assistant,
 * a script, an automation) against
 * the `/api/external/v1` API. One user may hold several (capped by `ApiTokenService`).
 *
 * Only [tokenHash] — the SHA-256 hex digest of the secret — is persisted. The plaintext is
 * returned to the user exactly once, at creation, and is unrecoverable afterwards. This is
 * deliberately unlike the single-use capability tokens elsewhere in the schema
 * (`email_login_token`, `board_invitation`), which still store their secret in the clear.
 *
 * A token is usable when [revokedAt] is null and [expiresAt] is either null or in the future.
 */
@Entity
@Table(name = "api_token")
class ApiTokenEntity {
    @Id
    var id: UUID? = null

    @Column(name = "user_id", nullable = false)
    var userId: UUID? = null

    /** User-supplied label, shown in Settings so a token can be recognized before revoking it. */
    @Column(nullable = false, length = 100)
    var name: String? = null

    /** SHA-256 hex of the plaintext token. The lookup key — never the secret itself. */
    @Column(name = "token_hash", nullable = false, length = 64)
    var tokenHash: String? = null

    /** Non-secret leading fragment (e.g. `blf_a1b2c3d4`), for display only. */
    @Column(nullable = false, length = 16)
    var prefix: String? = null

    /** One of [ApiTokenScope]. Stored as a plain string so new scopes need no schema change. */
    @Column(nullable = false, length = 16)
    var scope: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    /** Best-effort last-use stamp; written at most once a minute per token (see `ApiTokenService`). */
    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null

    /** Null means the token never expires. */
    @Column(name = "expires_at")
    var expiresAt: Instant? = null

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
}

/**
 * Token scopes. [WRITE] implies [READ] — the external filter chain grants `EXTERNAL_READ` to every
 * valid token and adds `EXTERNAL_WRITE` only for write-scoped ones.
 */
object ApiTokenScope {
    const val READ = "read"
    const val WRITE = "write"

    val allowedValues = setOf(READ, WRITE)
}
