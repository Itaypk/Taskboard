package dev.itayp.tasker.service

import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.config.AuthProperties
import dev.itayp.tasker.crypto.newTestUserCryptoService
import dev.itayp.tasker.jpa.AuthIdentityEntity
import dev.itayp.tasker.jpa.AuthProvider
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.AuthIdentityRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.security.google.GoogleAuthData
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class UserAuthServiceGoogleTest {

    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var authIdentityRepository: AuthIdentityRepository
    @Mock lateinit var userService: UserService
    @Mock lateinit var tutorialSeeder: TutorialSeeder

    private val fixedNow = Instant.parse("2026-11-15T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val crypto = newTestUserCryptoService()

    private fun service(registration: AuthProperties.RegistrationMode = AuthProperties.RegistrationMode.OPEN) =
        UserAuthService(
            userRepository, authIdentityRepository, userService, tutorialSeeder, crypto, AppProperties(),
            RegistrationPolicy(AuthProperties(registration = registration)), clock,
        )

    private val ownerId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val gmail = "alice@gmail.com"

    private fun google(email: String? = gmail, verified: Boolean = true, hd: String? = null) =
        GoogleAuthData(subject = "g-123", email = email, emailVerified = verified, hostedDomain = hd)

    private fun owner(verified: Boolean = true) = UserEntity().apply {
        id = ownerId
        emailHash = EmailHasher.hash(gmail)
        emailVerifiedAt = if (verified) Instant.parse("2026-01-01T00:00:00Z") else null
        claimed = true
    }

    private fun savesPassThrough() {
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }
    }

    @Test
    fun `a known Google account logs into its user`() {
        val user = UserEntity().apply { id = ownerId }
        val identity = AuthIdentityEntity().apply { userId = ownerId; provider = AuthProvider.GOOGLE; providerUserId = "g-123" }
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "g-123")).thenReturn(identity)
        whenever(userRepository.findById(ownerId)).thenReturn(Optional.of(user))
        savesPassThrough()

        val outcome = service().loginByGoogle(google())

        assertThat(outcome).isEqualTo(GoogleLoginOutcome.Success(user))
        assertThat(user.lastLoginAt).isEqualTo(fixedNow)
        verify(userService, never()).initializeNewUser(any(), any(), any())
    }

    @Test
    fun `a new Gmail account registers with the address as its verified email and email login`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(any(), any())).thenReturn(null)
        whenever(userRepository.findByEmailHash(EmailHasher.hash(gmail))).thenReturn(null)
        savesPassThrough()

        val outcome = service().loginByGoogle(google(email = " Alice@Gmail.com "))

        val user = (outcome as GoogleLoginOutcome.Success).user
        assertThat(user.emailHash).isEqualTo(EmailHasher.hash(gmail))
        assertThat(user.emailVerifiedAt).isEqualTo(fixedNow)
        assertThat(crypto.decrypt(user.id!!, user.email)).isEqualTo(gmail)
        val identities = argumentCaptor<AuthIdentityEntity>()
        verify(authIdentityRepository, times(2)).save(identities.capture())
        assertThat(identities.allValues.map { it.provider to it.providerUserId })
            .containsExactly(AuthProvider.GOOGLE to "g-123", AuthProvider.EMAIL to EmailHasher.hash(gmail))
        verify(userService).initializeNewUser(eq(user.id!!), any(), eq(true))
    }

    @Test
    fun `a new account with an untrusted email registers without storing the address`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "g-123")).thenReturn(null)
        whenever(userRepository.findByEmailHash(any())).thenReturn(null)
        savesPassThrough()

        val outcome = service().loginByGoogle(google(email = "alice@outlook.com"))

        val user = (outcome as GoogleLoginOutcome.Success).user
        assertThat(user.emailHash).isNull()
        assertThat(user.email).isNull()
        val identities = argumentCaptor<AuthIdentityEntity>()
        verify(authIdentityRepository).save(identities.capture())
        assertThat(identities.firstValue.provider).isEqualTo(AuthProvider.GOOGLE)
    }

    @Test
    fun `a trusted email matching a verified account signs into it and attaches Google`() {
        val owner = owner()
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "g-123")).thenReturn(null)
        whenever(userRepository.findByEmailHash(EmailHasher.hash(gmail))).thenReturn(owner)
        whenever(authIdentityRepository.findAllByUserId(ownerId)).thenReturn(emptyList())
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.EMAIL, EmailHasher.hash(gmail)))
            .thenReturn(AuthIdentityEntity())
        savesPassThrough()

        val outcome = service().loginByGoogle(google())

        assertThat(outcome).isEqualTo(GoogleLoginOutcome.Success(owner))
        assertThat(owner.lastLoginAt).isEqualTo(fixedNow)
        val identity = argumentCaptor<AuthIdentityEntity>()
        verify(authIdentityRepository).save(identity.capture())
        assertThat(identity.firstValue.userId).isEqualTo(ownerId)
        assertThat(identity.firstValue.provider).isEqualTo(AuthProvider.GOOGLE)
        verify(userService, never()).initializeNewUser(any(), any(), any())
    }

    @Test
    fun `a Workspace account counts as a trusted email`() {
        val workspace = "alice@acme.test"
        val owner = owner().apply { emailHash = EmailHasher.hash(workspace) }
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "g-123")).thenReturn(null)
        whenever(userRepository.findByEmailHash(EmailHasher.hash(workspace))).thenReturn(owner)
        whenever(authIdentityRepository.findAllByUserId(ownerId)).thenReturn(emptyList())
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.EMAIL, EmailHasher.hash(workspace)))
            .thenReturn(AuthIdentityEntity())
        savesPassThrough()

        val outcome = service().loginByGoogle(google(email = workspace, hd = "acme.test"))

        assertThat(outcome).isEqualTo(GoogleLoginOutcome.Success(owner))
    }

    @Test
    fun `an untrusted email matching an account is refused rather than merged`() {
        val outlook = "alice@outlook.com"
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "g-123")).thenReturn(null)
        whenever(userRepository.findByEmailHash(EmailHasher.hash(outlook)))
            .thenReturn(owner().apply { emailHash = EmailHasher.hash(outlook) })
        whenever(authIdentityRepository.findAllByUserId(ownerId)).thenReturn(emptyList())

        assertThat(service().loginByGoogle(google(email = outlook))).isEqualTo(GoogleLoginOutcome.EmailConflict)
        verify(authIdentityRepository, never()).save(any<AuthIdentityEntity>())
    }

    @Test
    fun `a matching account that never verified the address is refused`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "g-123")).thenReturn(null)
        whenever(userRepository.findByEmailHash(EmailHasher.hash(gmail))).thenReturn(owner(verified = false))
        whenever(authIdentityRepository.findAllByUserId(ownerId)).thenReturn(emptyList())

        assertThat(service().loginByGoogle(google())).isEqualTo(GoogleLoginOutcome.EmailConflict)
        verify(authIdentityRepository, never()).save(any<AuthIdentityEntity>())
    }

    @Test
    fun `a matching account that already has another Google account is refused`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "g-123")).thenReturn(null)
        whenever(userRepository.findByEmailHash(EmailHasher.hash(gmail))).thenReturn(owner())
        whenever(authIdentityRepository.findAllByUserId(ownerId))
            .thenReturn(listOf(AuthIdentityEntity().apply { provider = AuthProvider.GOOGLE; providerUserId = "g-other" }))

        assertThat(service().loginByGoogle(google())).isEqualTo(GoogleLoginOutcome.EmailConflict)
        verify(authIdentityRepository, never()).save(any<AuthIdentityEntity>())
    }

    @Test
    fun `closed registration refuses a new Google account`() {
        whenever(authIdentityRepository.findByProviderAndProviderUserId(AuthProvider.GOOGLE, "g-123")).thenReturn(null)
        whenever(userRepository.findByEmailHash(any())).thenReturn(null)

        assertThatThrownBy { service(AuthProperties.RegistrationMode.CLOSED).loginByGoogle(google()) }
            .isInstanceOf(RegistrationClosedException::class.java)
        verify(userRepository, never()).save(any<UserEntity>())
    }
}
