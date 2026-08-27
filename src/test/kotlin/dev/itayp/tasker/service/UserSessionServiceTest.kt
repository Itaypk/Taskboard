package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.security.SessionAuthenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.MapSession
import org.springframework.session.Session
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class UserSessionServiceTest {

    private val userId: UUID = UUID.randomUUID()
    private val repository = mock<FindByIndexNameSessionRepository<Session>>()
    private val crypto = mock<UserCryptoService>()
    private val service = UserSessionService(repository, crypto)

    @Test
    fun `lists sessions with the current device first, then most recently active`() {
        val current = session("current", lastAccessed = Instant.parse("2026-08-01T00:00:00Z"))
        val stale = session("stale", lastAccessed = Instant.parse("2026-08-10T00:00:00Z"))
        val recent = session("recent", lastAccessed = Instant.parse("2026-08-26T00:00:00Z"))
        givenSessions(current, stale, recent)

        val result = service.list(userId, currentSessionId = "current")

        // The current session sorts first even though it is the least recently active of the three.
        assertThat(result.map { it.current }).containsExactly(true, false, false)
        assertThat(result.map { it.lastActiveAt }).containsExactly(
            "2026-08-01T00:00:00Z", "2026-08-26T00:00:00Z", "2026-08-10T00:00:00Z",
        )
    }

    @Test
    fun `decrypts the stored ip and reports the signed-in time from the authedAt anchor`() {
        val authedAt = Instant.parse("2026-07-04T08:00:00Z")
        val ciphertext = byteArrayOf(1, 2, 3)
        val session = session("a", lastAccessed = Instant.parse("2026-08-26T00:00:00Z")).apply {
            setAttribute(SessionAuthenticator.AUTHED_AT_ATTRIBUTE, authedAt.epochSecond)
            setAttribute(SessionAuthenticator.IP_ATTRIBUTE, ciphertext)
            setAttribute(SessionAuthenticator.DEVICE_ATTRIBUTE, "Chrome on macOS")
        }
        givenSessions(session)
        whenever(crypto.decrypt(userId, ciphertext)).thenReturn("203.0.113.7")

        val result = service.list(userId, currentSessionId = "a").single()

        assertThat(result.device).isEqualTo("Chrome on macOS")
        assertThat(result.ipAddress).isEqualTo("203.0.113.7")
        assertThat(result.signedInAt).isEqualTo(authedAt.toString())
    }

    @Test
    fun `a session predating the metadata still lists, using creation time`() {
        // Sessions already in SPRING_SESSION when this shipped carry no attributes at all. The
        // whole screen must not fall over because of them.
        val created = Instant.parse("2026-05-01T00:00:00Z")
        givenSessions(session("legacy", lastAccessed = Instant.parse("2026-08-26T00:00:00Z"), created = created))

        val result = service.list(userId, currentSessionId = "legacy").single()

        assertThat(result.device).isNull()
        assertThat(result.ipAddress).isNull()
        assertThat(result.signedInAt).isEqualTo(created.toString())
    }

    @Test
    fun `an undecryptable ip degrades to null instead of failing the whole list`() {
        val session = session("a", lastAccessed = Instant.now()).apply {
            setAttribute(SessionAuthenticator.IP_ATTRIBUTE, byteArrayOf(9))
        }
        givenSessions(session)
        whenever(crypto.decrypt(any(), any())).thenThrow(IllegalStateException("bad key"))

        assertThat(service.list(userId, "a").single().ipAddress).isNull()
    }

    @Test
    fun `revokeOthers deletes every session but the current one`() {
        givenSessions(session("current"), session("other-1"), session("other-2"))

        val revoked = service.revokeOthers(userId, currentSessionId = "current")

        assertThat(revoked).isEqualTo(2)
        verify(repository).deleteById("other-1")
        verify(repository).deleteById("other-2")
        verify(repository, never()).deleteById("current")
    }

    @Test
    fun `revokeOthers is a no-op when this is the only session`() {
        givenSessions(session("current"))

        assertThat(service.revokeOthers(userId, currentSessionId = "current")).isZero()
        verify(repository, never()).deleteById(any())
    }

    private fun givenSessions(vararg sessions: MapSession) {
        val byId: Map<String, Session> = sessions.associateBy { it.id }
        whenever(repository.findByPrincipalName(userId.toString())).thenReturn(byId)
    }

    private fun session(
        id: String,
        lastAccessed: Instant = Instant.parse("2026-08-26T00:00:00Z"),
        created: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    ) = MapSession(id).apply {
        creationTime = created
        setLastAccessedTime(lastAccessed)
    }
}
