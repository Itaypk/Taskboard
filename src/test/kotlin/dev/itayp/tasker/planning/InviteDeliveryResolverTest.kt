package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.crypto.noopUserCryptoService
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Locale
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@ExtendWith(MockitoExtension::class)
class InviteDeliveryResolverTest {

    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var userSettingsService: UserSettingsService

    private val crypto = noopUserCryptoService()
    private val userId = UUID.randomUUID()

    private fun resolver(emailEnabled: Boolean = true) = InviteDeliveryResolver(
        userRepository,
        userSettingsService,
        crypto,
        EmailProperties(enabled = emailEnabled),
    )

    private fun stubVerifiedOptIn(email: String = "alice@example.com") {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(calendarInviteEmail = true))
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(verifiedUser(email)))
    }

    @Test
    fun `resolves email context when enabled, opted in, and verified`() {
        stubVerifiedOptIn()

        val ctx = resolver().resolveEmailContext(userId)

        assertEquals("alice@example.com", ctx?.email)
        assertEquals(Locale.ENGLISH, ctx?.locale)
    }

    @Test
    fun `returns null when email integration is globally disabled`() {
        assertNull(resolver(emailEnabled = false).resolveEmailContext(userId))
    }

    @Test
    fun `returns null when calendarInviteEmail is off`() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(calendarInviteEmail = false))

        assertNull(resolver().resolveEmailContext(userId))
    }

    @Test
    fun `returns null when email is not verified`() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(calendarInviteEmail = true))
        val user = UserEntity().apply {
            this.id = userId
            this.email = "unverified@example.com".toByteArray(Charsets.UTF_8)
            this.emailVerifiedAt = null
        }
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))

        assertNull(resolver().resolveEmailContext(userId))
    }

    @Test
    fun `returns null when the user is not found`() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(calendarInviteEmail = true))
        whenever(userRepository.findById(userId)).thenReturn(Optional.empty())

        assertNull(resolver().resolveEmailContext(userId))
    }

    private fun verifiedUser(email: String) = UserEntity().apply {
        this.id = userId
        this.email = email.toByteArray(Charsets.UTF_8)
        this.emailVerifiedAt = Instant.now()
    }

    private fun settings(calendarInviteEmail: Boolean) = UserSettings(
        userId = userId,
        displayName = null,
        contextBlock = null,
        timeZone = "UTC",
        preferredLanguage = "en-US",
        calendarInviteEmail = calendarInviteEmail,
        gender = null,
        agentDescription = null,
        planningCron = null,
        weekStartDay = null,
        autoArchiveDays = null,
    )
}
