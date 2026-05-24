package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.newTestUserCryptoService
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.*

@ExtendWith(MockitoExtension::class)
class UserAuthServiceTest {

    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var userService: UserService
    @Mock lateinit var demoDataSeeder: DemoDataSeeder

    private val fixedNow = Instant.parse("2026-04-21T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val crypto = newTestUserCryptoService()

    private val service: UserAuthService by lazy {
        UserAuthService(userRepository, userService, demoDataSeeder, crypto, clock)
    }

    private fun authData(telegramId: Long = 42L) = TelegramAuthData(
        telegramId = telegramId,
        username = "alice",
        firstName = "Alice",
        photoUrl = "https://t.me/a.png",
        authDate = fixedNow,
    )

    @Test
    fun `new telegram id creates user and seeds default categories`() {
        whenever(userRepository.findByTelegramId(42L)).thenReturn(null)
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val saved = service.loginOrRegisterByTelegram(authData())

        assertThat(saved.telegramId).isEqualTo(42L)
        assertThat(saved.telegramUsername).isEqualTo("alice")
        assertThat(saved.createdAt).isEqualTo(fixedNow)
        assertThat(saved.lastLoginAt).isEqualTo(fixedNow)
        verify(userService).initializeNewUser(eq(saved.id!!))
    }

    @Test
    fun `existing telegram id updates fields and does not re-seed categories`() {
        val existing = UserEntity().apply {
            id = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
            telegramId = 42L
            telegramUsername = "old"
            createdAt = Instant.parse("2026-01-01T00:00:00Z")
            lastLoginAt = Instant.parse("2026-01-01T00:00:00Z")
        }
        whenever(userRepository.findByTelegramId(42L)).thenReturn(existing)
        val captor = argumentCaptor<UserEntity>()
        whenever(userRepository.save(captor.capture())).thenAnswer { it.arguments[0] as UserEntity }

        val saved = service.loginOrRegisterByTelegram(authData())

        assertThat(saved.id).isEqualTo(existing.id)
        assertThat(saved.telegramUsername).isEqualTo("alice")
        assertThat(saved.lastLoginAt).isEqualTo(fixedNow)
        assertThat(saved.createdAt).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
        verify(userService, never()).initializeNewUser(any())
    }

    @Test
    fun `ensureDevUser returns existing user without re-seeding`() {
        val devId = UUID.fromString("00000000-0000-0000-0000-0000000000dd")
        val existing = UserEntity().apply { id = devId; telegramId = 0L }
        whenever(userRepository.findById(devId)).thenReturn(Optional.of(existing))

        val result = service.ensureDevUser(devId, 0L)

        assertThat(result).isSameAs(existing)
        verify(userService, never()).initializeNewUser(any())
        verify(userRepository, never()).save(any<UserEntity>())
    }

    @Test
    fun `ensureDevUser creates and seeds when user is missing`() {
        val devId = UUID.fromString("00000000-0000-0000-0000-0000000000dd")
        whenever(userRepository.findById(devId)).thenReturn(Optional.empty())
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val result = service.ensureDevUser(devId, 99L)

        assertThat(result.id).isEqualTo(devId)
        assertThat(result.telegramId).isEqualTo(99L)
        assertThat(result.createdAt).isEqualTo(fixedNow)
        verify(userService).initializeNewUser(eq(devId))
    }
}
