package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.ApiTokenEntity
import dev.itayp.tasker.jpa.ApiTokenScope
import dev.itayp.tasker.repository.ApiTokenRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ApiTokenServiceTest {

    @Mock lateinit var repository: ApiTokenRepository

    private val fixedNow = Instant.parse("2026-08-24T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val userId = UUID.randomUUID()

    private val service: ApiTokenService by lazy { ApiTokenService(repository, clock) }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun storedToken(
        plaintext: String,
        scope: String = ApiTokenScope.WRITE,
        revokedAt: Instant? = null,
        expiresAt: Instant? = null,
    ) = ApiTokenEntity().apply {
        this.id = UUID.randomUUID()
        this.userId = this@ApiTokenServiceTest.userId
        this.name = "test"
        this.tokenHash = sha256Hex(plaintext)
        this.prefix = plaintext.take(12)
        this.scope = scope
        this.createdAt = fixedNow
        this.revokedAt = revokedAt
        this.expiresAt = expiresAt
    }

    @Test
    fun `created token is prefixed, high-entropy, and stored only as a hash`() {
        whenever(repository.countUsable(userId, fixedNow)).thenReturn(0L)
        whenever(repository.save(any<ApiTokenEntity>())).thenAnswer { it.arguments[0] }

        val created = service.createToken(userId, "Claude Code", ApiTokenScope.WRITE)

        assertThat(created.plaintext).startsWith("blf_")
        // 32 random bytes base64url-encoded without padding is 43 chars.
        assertThat(created.plaintext).hasSize("blf_".length + 43)

        val entity = created.token
        // The secret itself must never be persisted — only its digest.
        assertThat(entity.tokenHash).isEqualTo(sha256Hex(created.plaintext))
        assertThat(entity.tokenHash).isNotEqualTo(created.plaintext)
        assertThat(entity.prefix).isEqualTo(created.plaintext.take(12))
        assertThat(entity.scope).isEqualTo(ApiTokenScope.WRITE)
        assertThat(entity.createdAt).isEqualTo(fixedNow)
    }

    @Test
    fun `a token minted without a lifetime never expires`() {
        whenever(repository.countUsable(userId, fixedNow)).thenReturn(0L)
        whenever(repository.save(any<ApiTokenEntity>())).thenAnswer { it.arguments[0] }

        val created = service.createToken(userId, "forever", ApiTokenScope.READ)

        assertThat(created.token.expiresAt).isNull()
    }

    @Test
    fun `a token minted with a lifetime expires that long after creation`() {
        whenever(repository.countUsable(userId, fixedNow)).thenReturn(0L)
        whenever(repository.save(any<ApiTokenEntity>())).thenAnswer { it.arguments[0] }

        val created = service.createToken(userId, "quarterly", ApiTokenScope.READ, Duration.ofDays(90))

        assertThat(created.token.expiresAt).isEqualTo(fixedNow.plus(Duration.ofDays(90)))
    }

    @Test
    fun `a non-positive lifetime is rejected`() {
        assertThatThrownBy { service.createToken(userId, "bad", ApiTokenScope.READ, Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `two tokens never collide`() {
        whenever(repository.countUsable(userId, fixedNow)).thenReturn(0L)
        whenever(repository.save(any<ApiTokenEntity>())).thenAnswer { it.arguments[0] }

        val first = service.createToken(userId, "one", ApiTokenScope.READ).plaintext
        val second = service.createToken(userId, "two", ApiTokenScope.READ).plaintext

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `creating beyond the per-user cap is refused`() {
        whenever(repository.countUsable(userId, fixedNow))
            .thenReturn(ApiTokenService.MAX_LIVE_TOKENS_PER_USER.toLong())

        assertThatThrownBy { service.createToken(userId, "one too many", ApiTokenScope.READ) }
            .isInstanceOf(ApiTokenLimitExceededException::class.java)

        verify(repository, never()).save(any<ApiTokenEntity>())
    }

    @Test
    fun `an unknown scope is rejected`() {
        assertThatThrownBy { service.createToken(userId, "bad", "admin") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `resolve returns the owning user and scope for a live token`() {
        val plaintext = "blf_livetoken"
        whenever(repository.findByTokenHash(sha256Hex(plaintext))).thenReturn(storedToken(plaintext))

        val resolved = service.resolve(plaintext)

        assertThat(resolved).isNotNull
        assertThat(resolved!!.userId).isEqualTo(userId)
        assertThat(resolved.scope).isEqualTo(ApiTokenScope.WRITE)
    }

    @Test
    fun `resolve rejects an unknown token`() {
        whenever(repository.findByTokenHash(any())).thenReturn(null)

        assertThat(service.resolve("blf_nosuchtoken")).isNull()
    }

    @Test
    fun `resolve rejects a blank token without touching the database`() {
        assertThat(service.resolve("   ")).isNull()

        verify(repository, never()).findByTokenHash(any())
    }

    @Test
    fun `resolve rejects a revoked token`() {
        val plaintext = "blf_revoked"
        whenever(repository.findByTokenHash(sha256Hex(plaintext)))
            .thenReturn(storedToken(plaintext, revokedAt = fixedNow.minusSeconds(60)))

        assertThat(service.resolve(plaintext)).isNull()
    }

    @Test
    fun `resolve rejects an expired token`() {
        val plaintext = "blf_expired"
        whenever(repository.findByTokenHash(sha256Hex(plaintext)))
            .thenReturn(storedToken(plaintext, expiresAt = fixedNow.minusSeconds(1)))

        assertThat(service.resolve(plaintext)).isNull()
    }

    @Test
    fun `resolve accepts a token whose expiry is still in the future`() {
        val plaintext = "blf_notyetexpired"
        whenever(repository.findByTokenHash(sha256Hex(plaintext)))
            .thenReturn(storedToken(plaintext, expiresAt = fixedNow.plus(Duration.ofDays(1))))

        assertThat(service.resolve(plaintext)).isNotNull
    }

    @Test
    fun `repeated resolves within the throttle window write last-used only once`() {
        val plaintext = "blf_busytoken"
        val entity = storedToken(plaintext)
        whenever(repository.findByTokenHash(sha256Hex(plaintext))).thenReturn(entity)

        repeat(5) { service.resolve(plaintext) }

        // Reads must not turn into a write per request.
        verify(repository).touchLastUsed(eq(entity.id!!), any())
    }

    @Test
    fun `revoking marks the token revoked and blocks further resolution`() {
        val plaintext = "blf_tobrevoked"
        val entity = storedToken(plaintext)
        whenever(repository.findByIdAndUserId(entity.id!!, userId)).thenReturn(entity)
        whenever(repository.save(any<ApiTokenEntity>())).thenAnswer { it.arguments[0] }

        assertThat(service.revokeToken(userId, entity.id!!)).isTrue()
        assertThat(entity.revokedAt).isEqualTo(fixedNow)
    }

    @Test
    fun `revoking an unknown token reports false rather than throwing`() {
        val unknownId = UUID.randomUUID()
        whenever(repository.findByIdAndUserId(unknownId, userId)).thenReturn(null)

        assertThat(service.revokeToken(userId, unknownId)).isFalse()
    }

    @Test
    fun `revoking another user's token is refused`() {
        val otherUser = UUID.randomUUID()
        val tokenId = UUID.randomUUID()
        whenever(repository.findByIdAndUserId(tokenId, otherUser)).thenReturn(null)

        assertThat(service.revokeToken(otherUser, tokenId)).isFalse()
        verify(repository, never()).save(any<ApiTokenEntity>())
    }

    @Test
    fun `listing hides already-revoked tokens`() {
        val live = storedToken("blf_live")
        val revoked = storedToken("blf_dead", revokedAt = fixedNow)
        whenever(repository.findAllByUserIdOrderByCreatedAtDesc(userId)).thenReturn(listOf(live, revoked))

        assertThat(service.listTokens(userId)).containsExactly(live)
    }

    @Test
    fun `listing keeps expired tokens so the user can see why an automation stopped`() {
        val expired = storedToken("blf_old", expiresAt = fixedNow.minusSeconds(1))
        whenever(repository.findAllByUserIdOrderByCreatedAtDesc(userId)).thenReturn(listOf(expired))

        assertThat(service.listTokens(userId)).containsExactly(expired)
    }
}
