package dev.itayp.tasker.service

import dev.itayp.nescioquid.telegram.TelegramAuthData
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.config.AuthProperties
import dev.itayp.tasker.crypto.newTestUserCryptoService
import dev.itayp.tasker.jpa.AuthIdentityEntity
import dev.itayp.tasker.jpa.AuthProvider
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.AuthIdentityRepository
import dev.itayp.tasker.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    @Mock lateinit var authIdentityRepository: AuthIdentityRepository
    @Mock lateinit var userService: UserService
    @Mock lateinit var tutorialSeeder: TutorialSeeder

    private val fixedNow = Instant.parse("2026-04-21T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val crypto = newTestUserCryptoService()
    private val appProperties = AppProperties()
    private val openRegistration = RegistrationPolicy(AuthProperties())
    private val closedRegistration = RegistrationPolicy(AuthProperties(registration = AuthProperties.RegistrationMode.CLOSED))

    private val service: UserAuthService by lazy { serviceWith(openRegistration) }

    private fun serviceWith(policy: RegistrationPolicy, app: AppProperties = appProperties) =
        UserAuthService(userRepository, authIdentityRepository, userService, tutorialSeeder, crypto, app, policy, clock)

    private fun authData(telegramId: Long = 42L) = TelegramAuthData(
        telegramId = telegramId,
        username = "alice",
        firstName = "Alice",
        photoUrl = "https://t.me/a.png",
        authDate = fixedNow,
    )

    @Test
    fun `new telegram identity creates user, attaches identity, and seeds default categories`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.TELEGRAM, "42")).thenReturn(null)
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val saved = service.loginOrRegisterByTelegram(authData())

        assertThat(saved.telegramId).isEqualTo(42L)
        assertThat(saved.telegramUsername).isEqualTo("alice")
        assertThat(saved.createdAt).isEqualTo(fixedNow)
        assertThat(saved.lastLoginAt).isEqualTo(fixedNow)
        verify(userService).initializeNewUser(eq(saved.id!!), anyOrNull(), eq(true))

        val identity = argumentCaptor<AuthIdentityEntity>()
        verify(authIdentityRepository).save(identity.capture())
        assertThat(identity.firstValue.userId).isEqualTo(saved.id)
        assertThat(identity.firstValue.provider).isEqualTo(AuthProvider.TELEGRAM)
        assertThat(identity.firstValue.providerUserId).isEqualTo("42")
        assertThat(identity.firstValue.verifiedAt).isEqualTo(fixedNow)
    }

    @Test
    fun `registration forwards the locale hint to initializeNewUser`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.TELEGRAM, "42")).thenReturn(null)
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val saved = service.loginOrRegisterByTelegram(authData(), localeHint = "he")

        verify(userService).initializeNewUser(eq(saved.id!!), eq("he"), eq(true))
    }

    @Test
    fun `existing telegram identity updates fields and does not re-seed categories`() {
        val existing = UserEntity().apply {
            id = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
            telegramId = 42L
            telegramUsername = "old"
            createdAt = Instant.parse("2026-01-01T00:00:00Z")
            lastLoginAt = Instant.parse("2026-01-01T00:00:00Z")
        }
        // Pre-existing users already have a DEK from their original registration.
        crypto.ensureUserKey(existing.id!!)
        val identity = AuthIdentityEntity().apply {
            id = UUID.randomUUID()
            userId = existing.id
            provider = AuthProvider.TELEGRAM
            providerUserId = "42"
            verifiedAt = existing.createdAt
        }
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.TELEGRAM, "42")).thenReturn(identity)
        whenever(userRepository.findById(existing.id!!)).thenReturn(Optional.of(existing))
        val captor = argumentCaptor<UserEntity>()
        whenever(userRepository.save(captor.capture())).thenAnswer { it.arguments[0] as UserEntity }

        val saved = service.loginOrRegisterByTelegram(authData())

        assertThat(saved.id).isEqualTo(existing.id)
        assertThat(saved.telegramUsername).isEqualTo("alice")
        assertThat(saved.lastLoginAt).isEqualTo(fixedNow)
        assertThat(saved.createdAt).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
        verify(userService, never()).initializeNewUser(any(), anyOrNull(), any())
    }

    @Test
    fun `ensureDevUser returns existing user without re-seeding`() {
        val devId = UUID.fromString("00000000-0000-0000-0000-0000000000dd")
        val existing = UserEntity().apply { id = devId; telegramId = 0L }
        whenever(userRepository.findById(devId)).thenReturn(Optional.of(existing))

        val result = service.ensureDevUser(devId, 0L)

        assertThat(result).isSameAs(existing)
        verify(userService, never()).initializeNewUser(any(), anyOrNull(), any())
        verify(userRepository, never()).save(any<UserEntity>())
    }

    @Test
    fun `ensureDevUser creates user, attaches identity, and seeds when user is missing`() {
        val devId = UUID.fromString("00000000-0000-0000-0000-0000000000dd")
        whenever(userRepository.findById(devId)).thenReturn(Optional.empty())
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val result = service.ensureDevUser(devId, 99L)

        assertThat(result.id).isEqualTo(devId)
        assertThat(result.telegramId).isEqualTo(99L)
        assertThat(result.createdAt).isEqualTo(fixedNow)
        verify(userService).initializeNewUser(eq(devId), anyOrNull(), eq(true))

        val identity = argumentCaptor<AuthIdentityEntity>()
        verify(authIdentityRepository).save(identity.capture())
        assertThat(identity.firstValue.userId).isEqualTo(devId)
        assertThat(identity.firstValue.provider).isEqualTo(AuthProvider.TELEGRAM)
        assertThat(identity.firstValue.providerUserId).isEqualTo("99")
    }

    @Test
    fun `loginByEmail registers a new account when the address is unknown`() {
        val email = "new@example.com"
        val hash = EmailHasher.hash(email)
        whenever(userRepository.findByEmailHash(hash)).thenReturn(null)
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.EMAIL, hash)).thenReturn(null)
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val outcome = service.loginByEmail(email)

        assertThat(outcome).isInstanceOf(EmailLoginOutcome.Success::class.java)
        val user = (outcome as EmailLoginOutcome.Success).user
        assertThat(user.emailHash).isEqualTo(hash)
        assertThat(user.emailVerifiedAt).isEqualTo(fixedNow)
        assertThat(crypto.decrypt(user.id!!, user.email)).isEqualTo(email)
        verify(userService).initializeNewUser(eq(user.id!!), anyOrNull(), eq(true))

        val identity = argumentCaptor<AuthIdentityEntity>()
        verify(authIdentityRepository).save(identity.capture())
        assertThat(identity.firstValue.provider).isEqualTo(AuthProvider.EMAIL)
        assertThat(identity.firstValue.providerUserId).isEqualTo(hash)
        assertThat(identity.firstValue.verifiedAt).isEqualTo(fixedNow)
    }

    @Test
    fun `loginByEmail normalises case and whitespace before hashing`() {
        val hash = EmailHasher.hash("new@example.com")
        whenever(userRepository.findByEmailHash(hash)).thenReturn(null)
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.EMAIL, hash)).thenReturn(null)
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val outcome = service.loginByEmail("  NEW@Example.com  ")

        val user = (outcome as EmailLoginOutcome.Success).user
        assertThat(user.emailHash).isEqualTo(hash)
        assertThat(crypto.decrypt(user.id!!, user.email)).isEqualTo("new@example.com")
    }

    @Test
    fun `loginByEmail logs into the existing owner of a verified address`() {
        val email = "owner@example.com"
        val hash = EmailHasher.hash(email)
        val existing = UserEntity().apply {
            id = UUID.fromString("00000000-0000-0000-0000-0000000000ee")
            emailHash = hash
            emailVerifiedAt = Instant.parse("2026-01-01T00:00:00Z")
            createdAt = Instant.parse("2026-01-01T00:00:00Z")
        }
        whenever(userRepository.findByEmailHash(hash)).thenReturn(existing)
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.EMAIL, hash))
            .thenReturn(AuthIdentityEntity().apply { userId = existing.id })
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val outcome = service.loginByEmail(email)

        assertThat(outcome).isInstanceOf(EmailLoginOutcome.Success::class.java)
        assertThat((outcome as EmailLoginOutcome.Success).user.id).isEqualTo(existing.id)
        assertThat(existing.lastLoginAt).isEqualTo(fixedNow)
        verify(userService, never()).initializeNewUser(any(), anyOrNull(), any())
        verify(authIdentityRepository, never()).save(any<AuthIdentityEntity>())
    }

    @Test
    fun `loginByEmail refuses an address owned by an unverified account`() {
        val email = "pending@example.com"
        val hash = EmailHasher.hash(email)
        val existing = UserEntity().apply {
            id = UUID.fromString("00000000-0000-0000-0000-0000000000ef")
            emailHash = hash
            emailVerifiedAt = null
        }
        whenever(userRepository.findByEmailHash(hash)).thenReturn(existing)

        val outcome = service.loginByEmail(email)

        assertThat(outcome).isEqualTo(EmailLoginOutcome.UnverifiedConflict)
        verify(userRepository, never()).save(any<UserEntity>())
        verify(userService, never()).initializeNewUser(any(), anyOrNull(), any())
    }

    @Test
    fun `createUnclaimedUser provisions a channel-less, unclaimed user seeded with the tutorial, always granted`() {
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val result = service.createUnclaimedUser()

        assertThat(result.claimed).isFalse()
        assertThat(result.lastActiveAt).isEqualTo(fixedNow)
        verify(userService).initializeNewUser(eq(result.id!!), anyOrNull(), eq(false))
        // Two matchers, not one: `seed`'s localeHint has a default, so this call site compiles
        // into seed(id, null) and Mockito requires a matcher per argument.
        verify(tutorialSeeder).seed(eq(result.id!!), anyOrNull())
        verify(authIdentityRepository, never()).save(any<AuthIdentityEntity>())
    }

    @Test
    fun `createUnclaimedUser refuses once the unclaimed-account cap is reached`() {
        val cappedService = serviceWith(openRegistration, appProperties.copy(unclaimedAccountCap = 5))
        whenever(userRepository.countByClaimed(false)).thenReturn(5L)

        assertThatThrownBy { cappedService.createUnclaimedUser() }
            .isInstanceOf(UnclaimedAccountCapExceededException::class.java)
        verify(userRepository, never()).save(any<UserEntity>())
        verify(tutorialSeeder, never()).seed(any(), anyOrNull())
    }

    @Test
    fun `closed registration refuses a new telegram identity without creating anything`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.TELEGRAM, "42")).thenReturn(null)

        assertThatThrownBy { serviceWith(closedRegistration).loginOrRegisterByTelegram(authData()) }
            .isInstanceOf(RegistrationClosedException::class.java)
        verify(userRepository, never()).save(any<UserEntity>())
        verify(authIdentityRepository, never()).save(any<AuthIdentityEntity>())
    }

    @Test
    fun `closed registration still logs in an existing identity`() {
        val ownerId = UUID.randomUUID()
        // Pre-existing users already have a DEK; the Telegram profile update encrypts under it.
        crypto.ensureUserKey(ownerId)
        val identity = AuthIdentityEntity().apply {
            id = UUID.randomUUID()
            userId = ownerId
            provider = AuthProvider.TELEGRAM
            providerUserId = "42"
        }
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.TELEGRAM, "42")).thenReturn(identity)
        whenever(userRepository.findById(ownerId)).thenReturn(Optional.of(UserEntity().apply { id = ownerId }))
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val user = serviceWith(closedRegistration).loginOrRegisterByTelegram(authData())

        assertThat(user.id).isEqualTo(ownerId)
    }

    @Test
    fun `closed registration still provisions an operator-listed local user`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.LOCAL, "alice")).thenReturn(null)
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val user = serviceWith(closedRegistration).loginOrRegister(AuthProvider.LOCAL, "alice", true, {}, {})

        assertThat(user.claimed).isTrue()
        verify(userService).initializeNewUser(eq(user.id!!), anyOrNull(), eq(true))
    }

    @Test
    fun `createUnclaimedUser refuses when the demo is unavailable`() {
        val demoOff = RegistrationPolicy(AuthProperties(demoEnabled = false))

        assertThatThrownBy { serviceWith(demoOff).createUnclaimedUser() }
            .isInstanceOf(RegistrationClosedException::class.java)
        assertThatThrownBy { serviceWith(closedRegistration).createUnclaimedUser() }
            .isInstanceOf(RegistrationClosedException::class.java)
        verify(userRepository, never()).save(any<UserEntity>())
    }
}
