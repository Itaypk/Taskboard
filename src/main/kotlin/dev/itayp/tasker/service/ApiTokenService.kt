package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.ApiTokenEntity
import dev.itayp.tasker.jpa.ApiTokenScope
import dev.itayp.tasker.repository.ApiTokenRepository
import dev.itayp.tasker.util.CapabilityTokens
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ResponseStatus
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A token that passed authentication — the caller identity plus what it's allowed to do. */
data class AuthenticatedApiToken(
    val tokenId: UUID,
    val userId: UUID,
    val scope: String,
)

/** A freshly minted token. [plaintext] is returned to the user once and never persisted. */
data class CreatedApiToken(
    val token: ApiTokenEntity,
    val plaintext: String,
)

@ResponseStatus(HttpStatus.CONFLICT)
class ApiTokenLimitExceededException(message: String) : RuntimeException(message)

/**
 * Mints, resolves and revokes the long-lived API tokens behind `/api/external/v1`.
 *
 * Security model:
 * - The secret is 32 bytes from [SecureRandom], URL-safe base64, prefixed `blf_`.
 * - Only the SHA-256 hex digest is stored ([CapabilityTokens.hash], shared with the email-link
 *   tokens). Unsalted is the right call: the input is 256 bits of uniform entropy, so there is no
 *   dictionary to run, and a fast digest is what allows an indexed single-row lookup on the hot
 *   authentication path.
 * - The plaintext is never logged. Log lines carry the token id only.
 */
@Service
class ApiTokenService(
    private val apiTokenRepository: ApiTokenRepository,
    private val clock: Clock,
) {
    private val random = SecureRandom()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * Throttles `last_used_at` writes so an active token doesn't turn every read request into a
     * database write. In-process, like [dev.itayp.tasker.ratelimit.InMemoryRateLimiter] — the app
     * runs as a single instance; this would need externalising alongside that one if it ever
     * doesn't. Worst case on a restart is one extra write per token.
     */
    private val lastUsedWrites = ConcurrentHashMap<UUID, Long>()

    @Transactional
    /** [lifetime] null means the token never expires. */
    fun createToken(userId: UUID, name: String, scope: String, lifetime: Duration? = null): CreatedApiToken {
        require(scope in ApiTokenScope.allowedValues) { "Unknown token scope: $scope" }
        require(lifetime == null || lifetime.isPositive) { "Token lifetime must be positive: $lifetime" }
        val now = clock.instant()
        // Expired tokens don't count: they no longer authenticate, so they shouldn't block a replacement.
        val live = apiTokenRepository.countUsable(userId, now)
        if (live >= MAX_LIVE_TOKENS_PER_USER) {
            throw ApiTokenLimitExceededException(
                "You already have $MAX_LIVE_TOKENS_PER_USER active API tokens. Revoke one to create another."
            )
        }

        val plaintext = generateToken()
        val entity = ApiTokenEntity().apply {
            this.id = UUID.randomUUID()
            this.userId = userId
            this.name = name.trim()
            this.tokenHash = CapabilityTokens.hash(plaintext)
            this.prefix = plaintext.take(PREFIX_LENGTH)
            this.scope = scope
            this.createdAt = now
            this.expiresAt = lifetime?.let { now.plus(it) }
        }
        val saved = apiTokenRepository.save(entity)
        logger.info(
            "Created API token {} (scope {}, expires {}) for user {}",
            saved.id, scope, saved.expiresAt ?: "never", userId,
        )
        return CreatedApiToken(saved, plaintext)
    }

    /**
     * Authenticates a raw bearer token. Returns null for anything unusable — unknown, revoked or
     * expired — so the caller can answer with a uniform 401 that doesn't distinguish the cases.
     */
    @Transactional
    fun resolve(rawToken: String): AuthenticatedApiToken? {
        if (rawToken.isBlank()) return null
        val entity = apiTokenRepository.findByTokenHash(CapabilityTokens.hash(rawToken)) ?: return null
        val tokenId = entity.id ?: return null
        val userId = entity.userId ?: return null

        if (entity.revokedAt != null) {
            logger.debug("Rejected revoked API token {}", tokenId)
            return null
        }
        val now = clock.instant()
        val expiresAt = entity.expiresAt
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            logger.debug("Rejected expired API token {}", tokenId)
            return null
        }

        touchLastUsed(tokenId, now)
        return AuthenticatedApiToken(tokenId, userId, entity.scope ?: ApiTokenScope.READ)
    }

    /**
     * Unrevoked tokens, expired ones included: an automation that just started getting 401s is
     * exactly when the user opens this list, and "expired on …" is the answer they're after.
     */
    @Transactional(readOnly = true)
    fun listTokens(userId: UUID): List<ApiTokenEntity> =
        apiTokenRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
            .filter { it.revokedAt == null }

    /** Idempotent: revoking an already-revoked or unknown token reports false rather than throwing. */
    @Transactional
    fun revokeToken(userId: UUID, tokenId: UUID): Boolean {
        val entity = apiTokenRepository.findByIdAndUserId(tokenId, userId) ?: return false
        if (entity.revokedAt != null) return false
        entity.revokedAt = clock.instant()
        apiTokenRepository.save(entity)
        lastUsedWrites.remove(tokenId)
        logger.info("Revoked API token {} for user {}", tokenId, userId)
        return true
    }

    private fun touchLastUsed(tokenId: UUID, now: Instant) {
        val nowMillis = now.toEpochMilli()
        val previous = lastUsedWrites[tokenId]
        if (previous != null && nowMillis - previous < LAST_USED_WRITE_INTERVAL_MILLIS) return
        lastUsedWrites[tokenId] = nowMillis
        apiTokenRepository.touchLastUsed(tokenId, now)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return TOKEN_PREFIX + urlEncoder.encodeToString(bytes)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ApiTokenService::class.java)

        const val TOKEN_PREFIX = "blf_"
        const val MAX_LIVE_TOKENS_PER_USER = 5

        /** 32 bytes = 256 bits, base64url-encoded to 43 chars. */
        private const val TOKEN_BYTES = 32

        /** `blf_` + the first 8 encoded chars — enough to tell tokens apart, useless as a secret. */
        private const val PREFIX_LENGTH = 12

        private const val LAST_USED_WRITE_INTERVAL_MILLIS = 60_000L
    }
}
