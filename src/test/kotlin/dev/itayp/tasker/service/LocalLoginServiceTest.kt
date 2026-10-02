package dev.itayp.tasker.service

import dev.itayp.tasker.config.AuthProperties
import dev.itayp.tasker.jpa.AuthProvider
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.ratelimit.RateLimiter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LocalLoginServiceTest {

    private val encoder = BCryptPasswordEncoder(4)
    private val aliceHash = encoder.encode("s3cret")!!
    private val userAuthService: UserAuthService = mock()
    private val allowAll = RateLimiter { true }

    private fun service(vararg users: String, limiter: RateLimiter = allowAll) =
        LocalLoginService(AuthProperties(local = AuthProperties.LocalUsers(users = users.toList())), userAuthService, limiter)

    @Test
    fun `a correct password logs in through the local identity, keyed by lowercase username`() {
        val user = UserEntity().apply { id = UUID.randomUUID() }
        whenever(userAuthService.loginOrRegister(eq(AuthProvider.LOCAL), eq("alice"), eq(true), any(), any(), anyOrNull()))
            .thenReturn(user)

        assertThat(service("Alice:$aliceHash").login(" ALICE ", "s3cret", "10.0.0.1")).isSameAs(user)
    }

    @Test
    fun `a wrong password or unknown username returns null without touching accounts`() {
        val service = service("alice:$aliceHash")

        assertThat(service.login("alice", "nope", "10.0.0.1")).isNull()
        assertThat(service.login("bob", "s3cret", "10.0.0.1")).isNull()
        verify(userAuthService, never()).loginOrRegister(any(), any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `an exhausted budget refuses before checking the password`() {
        val service = service("alice:$aliceHash", limiter = RateLimiter { key -> !key.startsWith("user:") })

        assertThatThrownBy { service.login("alice", "s3cret", "10.0.0.1") }
            .isInstanceOf(TooManyLoginAttemptsException::class.java)
        verify(userAuthService, never()).loginOrRegister(any(), any(), any(), any(), any(), anyOrNull())
    }

    @Test
    fun `no configured users means disabled`() {
        assertThat(service().enabled).isFalse()
        assertThat(service("alice:$aliceHash").enabled).isTrue()
    }

    @Test
    fun `reads an htpasswd-style file, skipping blanks and comments, and accepts 2y and {bcrypt} hashes`(@TempDir dir: Path) {
        val file = dir.resolve("users")
        val bobHash = encoder.encode("hunter2")!!.replaceFirst("\$2a\$", "\$2y\$")
        Files.writeString(file, "# operators\n\nbob:$bobHash\n")

        val users = LocalLoginService.loadUsers(
            AuthProperties.LocalUsers(users = listOf("alice:{bcrypt}$aliceHash"), usersFile = file.toString()),
        )

        assertThat(users).containsOnlyKeys("alice", "bob")
        assertThat(users["alice"]).isEqualTo(aliceHash)
    }

    @Test
    fun `a users file that doesn't exist fails with the setting's name`(@TempDir dir: Path) {
        val missing = dir.resolve("users").toString()

        assertThatThrownBy { LocalLoginService.loadUsers(AuthProperties.LocalUsers(usersFile = missing)) }
            .hasMessageContaining("TASKER_LOCAL_USERS_FILE").hasMessageContaining(missing)
    }

    @Test
    fun `rejects malformed entries without echoing the hash`() {
        fun load(vararg entries: String) = LocalLoginService.loadUsers(AuthProperties.LocalUsers(users = entries.toList()))

        assertThatThrownBy { load("alice") }.hasMessageContaining("username:bcrypt-hash")
        assertThatThrownBy { load("alice:plaintext-password") }
            .hasMessageContaining("bcrypt").hasMessageNotContaining("plaintext-password")
        assertThatThrownBy { load("bad user:$aliceHash") }.hasMessageContaining("must be 1-64 characters")
        assertThatThrownBy { load("alice:$aliceHash", "ALICE:$aliceHash") }.hasMessageContaining("more than once")
    }
}
