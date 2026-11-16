package dev.itayp.tasker.service

import dev.itayp.nescioquid.telegram.TelegramAuthData
import dev.itayp.tasker.crypto.newTestUserCryptoService
import dev.itayp.tasker.jpa.AuthIdentityEntity
import dev.itayp.tasker.jpa.AuthProvider
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.AuthIdentityRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.security.google.GoogleAuthData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class AccountLinkServiceTest {

    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var authIdentityRepository: AuthIdentityRepository
    @Mock lateinit var userSettingsService: UserSettingsService
    @Mock lateinit var eventPublisher: ApplicationEventPublisher

    private val fixedNow = Instant.parse("2026-04-21T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val crypto = newTestUserCryptoService()

    private val service by lazy {
        AccountLinkService(userRepository, authIdentityRepository, crypto, userSettingsService, eventPublisher, clock)
    }

    private val userId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")

    private fun telegramData(id: Long = 42L) = TelegramAuthData(
        telegramId = id, username = "bob", firstName = "Bob", photoUrl = "https://t.me/b.png", authDate = fixedNow,
    )

    @Test
    fun `linkTelegram attaches the identity, sets profile, and reschedules planning`() {
        crypto.ensureUserKey(userId)
        val user = UserEntity().apply { id = userId; telegramId = null }
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.TELEGRAM, "42")).thenReturn(null)
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val result = service.linkTelegram(userId, telegramData())

        assertThat(result).isEqualTo(LinkResult.Success)
        assertThat(user.telegramId).isEqualTo(42L)
        assertThat(crypto.decrypt(userId, user.telegramFirstName)).isEqualTo("Bob")
        val identity = argumentCaptor<AuthIdentityEntity>()
        verify(authIdentityRepository).save(identity.capture())
        assertThat(identity.firstValue.provider).isEqualTo(AuthProvider.TELEGRAM)
        assertThat(identity.firstValue.providerUserId).isEqualTo("42")
        verify(eventPublisher).publishEvent(UserPlanningScheduleChangedEvent(userId))
        verify(eventPublisher).publishEvent(TelegramLinkedEvent(userId))
        verify(userSettingsService).upgradeToStandardOnClaim(userId)
    }

    @Test
    fun `linkTelegram is idempotent when the identity already belongs to the same user`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.TELEGRAM, "42"))
            .thenReturn(AuthIdentityEntity().apply { this.userId = this@AccountLinkServiceTest.userId })

        val result = service.linkTelegram(userId, telegramData())

        assertThat(result).isEqualTo(LinkResult.AlreadyLinked)
        verify(authIdentityRepository, never()).save(any<AuthIdentityEntity>())
        verify(userSettingsService, never()).upgradeToStandardOnClaim(any())
    }

    @Test
    fun `linkTelegram refuses when the identity belongs to another account`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.TELEGRAM, "42"))
            .thenReturn(AuthIdentityEntity().apply { this.userId = UUID.randomUUID() })

        val result = service.linkTelegram(userId, telegramData())

        assertThat(result).isEqualTo(LinkResult.ConflictOwnedByAnother)
        verify(userRepository, never()).save(any<UserEntity>())
        verify(userSettingsService, never()).upgradeToStandardOnClaim(any())
    }

    @Test
    fun `linkTelegram refuses when the user already has a telegram account`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.TELEGRAM, "42")).thenReturn(null)
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity().apply { id = userId; telegramId = 7L }))

        val result = service.linkTelegram(userId, telegramData())

        assertThat(result).isEqualTo(LinkResult.AlreadyHasProvider)
        verify(authIdentityRepository, never()).save(any<AuthIdentityEntity>())
        verify(userSettingsService, never()).upgradeToStandardOnClaim(any())
    }

    private fun identity(provider: String) = AuthIdentityEntity().apply {
        id = UUID.randomUUID(); userId = this@AccountLinkServiceTest.userId; this.provider = provider
    }

    @Test
    fun `unlink telegram clears profile and reschedules when another method remains`() {
        val user = UserEntity().apply {
            id = userId; telegramId = 42L; telegramUsername = "bob"; telegramChatReadyAt = fixedNow
        }
        whenever(authIdentityRepository.findAllByUserId(userId))
            .thenReturn(listOf(identity(AuthProvider.TELEGRAM), identity(AuthProvider.EMAIL)))
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val result = service.unlink(userId, AuthProvider.TELEGRAM)

        assertThat(result).isEqualTo(UnlinkResult.Success)
        assertThat(user.telegramId).isNull()
        // A re-link (possibly to a different Telegram account) must prove its chat afresh.
        assertThat(user.telegramChatReadyAt).isNull()
        assertThat(user.telegramUsername).isNull()
        verify(authIdentityRepository).delete(any<AuthIdentityEntity>())
        verify(eventPublisher).publishEvent(UserPlanningScheduleChangedEvent(userId))
    }

    @Test
    fun `unlink email clears the address`() {
        val user = UserEntity().apply { id = userId; emailHash = "h"; emailVerifiedAt = fixedNow }
        whenever(authIdentityRepository.findAllByUserId(userId))
            .thenReturn(listOf(identity(AuthProvider.TELEGRAM), identity(AuthProvider.EMAIL)))
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val result = service.unlink(userId, AuthProvider.EMAIL)

        assertThat(result).isEqualTo(UnlinkResult.Success)
        assertThat(user.emailHash).isNull()
        assertThat(user.emailVerifiedAt).isNull()
    }

    @Test
    fun `unlink refuses to remove an operator-managed local login`() {
        whenever(authIdentityRepository.findAllByUserId(userId))
            .thenReturn(listOf(identity(AuthProvider.LOCAL), identity(AuthProvider.EMAIL)))

        val result = service.unlink(userId, AuthProvider.LOCAL)

        assertThat(result).isEqualTo(UnlinkResult.NotUnlinkable)
        verify(authIdentityRepository, never()).delete(any<AuthIdentityEntity>())
    }

    @Test
    fun `unlink refuses to remove the last login method`() {
        whenever(authIdentityRepository.findAllByUserId(userId)).thenReturn(listOf(identity(AuthProvider.TELEGRAM)))

        val result = service.unlink(userId, AuthProvider.TELEGRAM)

        assertThat(result).isEqualTo(UnlinkResult.WouldRemoveLastMethod)
        verify(authIdentityRepository, never()).delete(any<AuthIdentityEntity>())
    }

    @Test
    fun `unlink returns NotLinked when the provider isn't attached`() {
        whenever(authIdentityRepository.findAllByUserId(userId))
            .thenReturn(listOf(identity(AuthProvider.EMAIL), identity(AuthProvider.TELEGRAM)))

        val result = service.unlink(userId, AuthProvider.GOOGLE)

        assertThat(result).isEqualTo(UnlinkResult.NotLinked)
        verify(authIdentityRepository, never()).delete(any<AuthIdentityEntity>())
    }

    private fun googleData(email: String? = "bob@gmail.com") =
        GoogleAuthData(subject = "g-1", email = email, emailVerified = true, hostedDomain = null)

    @Test
    fun `linkGoogle attaches the identity, claims the account and adopts an unowned trusted email`() {
        crypto.ensureUserKey(userId)
        val user = UserEntity().apply { id = userId; claimed = false }
        val hash = EmailHasher.hash("bob@gmail.com")
        whenever(authIdentityRepository.findByProviderAndProviderUserId(any(), any())).thenReturn(null)
        whenever(authIdentityRepository.findAllByUserId(userId)).thenReturn(emptyList())
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        whenever(userRepository.findByEmailHash(hash)).thenReturn(null)
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val result = service.linkGoogle(userId, googleData())

        assertThat(result).isEqualTo(LinkResult.Success)
        assertThat(user.claimed).isTrue()
        assertThat(user.emailHash).isEqualTo(hash)
        assertThat(user.emailVerifiedAt).isEqualTo(fixedNow)
        assertThat(crypto.decrypt(userId, user.email)).isEqualTo("bob@gmail.com")
        val identities = argumentCaptor<AuthIdentityEntity>()
        verify(authIdentityRepository, times(2)).save(identities.capture())
        assertThat(identities.allValues.map { it.provider to it.providerUserId })
            .containsExactly(AuthProvider.GOOGLE to "g-1", AuthProvider.EMAIL to hash)
        verify(userSettingsService).upgradeToStandardOnClaim(userId)
    }

    @Test
    fun `linkGoogle leaves an existing email alone`() {
        val user = UserEntity().apply { id = userId; emailHash = "existing"; claimed = true }
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "g-1")).thenReturn(null)
        whenever(authIdentityRepository.findAllByUserId(userId)).thenReturn(emptyList())
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        assertThat(service.linkGoogle(userId, googleData())).isEqualTo(LinkResult.Success)
        assertThat(user.emailHash).isEqualTo("existing")
        verify(authIdentityRepository, times(1)).save(any<AuthIdentityEntity>())
    }

    @Test
    fun `linkGoogle refuses a Google account owned by someone else`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "g-1"))
            .thenReturn(AuthIdentityEntity().apply { this.userId = UUID.randomUUID() })

        assertThat(service.linkGoogle(userId, googleData())).isEqualTo(LinkResult.ConflictOwnedByAnother)
        verify(authIdentityRepository, never()).save(any<AuthIdentityEntity>())
    }

    @Test
    fun `linkGoogle refuses a second Google account`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "g-1")).thenReturn(null)
        whenever(authIdentityRepository.findAllByUserId(userId))
            .thenReturn(listOf(AuthIdentityEntity().apply { provider = AuthProvider.GOOGLE; providerUserId = "g-0" }))

        assertThat(service.linkGoogle(userId, googleData())).isEqualTo(LinkResult.AlreadyHasProvider)
        verify(authIdentityRepository, never()).save(any<AuthIdentityEntity>())
    }
}
