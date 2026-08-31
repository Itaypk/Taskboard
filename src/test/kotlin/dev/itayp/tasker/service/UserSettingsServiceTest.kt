package dev.itayp.tasker.service

import dev.itayp.tasker.ai.AiProperties
import dev.itayp.tasker.ai.AiTierCapProperties
import dev.itayp.tasker.ai.access.AiTier
import dev.itayp.tasker.crypto.noopUserCryptoService
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.request.UpdateUserSettingsRequest
import dev.itayp.tasker.repository.UserSettingsRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class UserSettingsServiceTest {

    @Mock lateinit var settingsRepository: UserSettingsRepository
    @Mock lateinit var eventPublisher: ApplicationEventPublisher

    private fun serviceWith(aiProperties: AiProperties) =
        UserSettingsService(settingsRepository, eventPublisher, noopUserCryptoService(), aiProperties)

    private val service by lazy { serviceWith(AiProperties()) }
    private val userId = UUID.randomUUID()

    private fun baseRequest(
        cron: String? = null,
        weekStart: String? = null,
        tz: String = "UTC",
    ) = UpdateUserSettingsRequest(
        displayName = null,
        contextBlock = null,
        timeZone = tz,
        preferredLanguage = "en-US",
        calendarInviteEmail = false,
        gender = null,
        agentDescription = null,
        planningCron = cron,
        weekStartDay = weekStart,
    )

    private fun stubExisting(cron: String? = null, tz: String = "UTC") {
        val existing = UserSettingsEntity().apply {
            this.userId = this@UserSettingsServiceTest.userId
            this.planningCron = cron
            this.timeZone = tz
        }
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(existing))
        whenever(settingsRepository.save(any<UserSettingsEntity>())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `rejects invalid cron`() {
        assertFailsWith<IllegalArgumentException> {
            service.update(userId, baseRequest(cron = "not a cron"))
        }
        verify(eventPublisher, never()).publishEvent(any<Any>())
    }

    @Test
    fun `rejects invalid week start day`() {
        assertFailsWith<IllegalArgumentException> {
            service.update(userId, baseRequest(weekStart = "FUNDAY"))
        }
    }

    @Test
    fun `publishes event when planning cron is added`() {
        stubExisting(cron = null)
        service.update(userId, baseRequest(cron = "0 30 9 * * MON", weekStart = "MONDAY"))
        verify(eventPublisher).publishEvent(eq(UserPlanningScheduleChangedEvent(userId)))
    }

    @Test
    fun `does not publish event when schedule unchanged`() {
        stubExisting(cron = "0 30 9 * * MON")
        service.update(userId, baseRequest(cron = "0 30 9 * * MON"))
        verify(eventPublisher, never()).publishEvent(any<Any>())
    }

    @Test
    fun `publishes event when time zone changes for scheduled user`() {
        stubExisting(cron = "0 30 9 * * MON", tz = "UTC")
        service.update(userId, baseRequest(cron = "0 30 9 * * MON", tz = "Europe/London"))
        verify(eventPublisher).publishEvent(eq(UserPlanningScheduleChangedEvent(userId)))
    }

    // ── appendToContextBlock ────────────────────────────────────────────────────
    // The noop crypto used here maps encrypt(s) -> s.toByteArray and decrypt(b) -> String(b),
    // so a stored context block is just its UTF-8 bytes.

    // Only findById is stubbed: appendToContextBlock ignores save()'s return, so leaving it unstubbed
    // (returns null, harmlessly) keeps strict stubbing happy in the paths that never save.
    private fun stubContext(existing: String?): UserSettingsEntity {
        val entity = UserSettingsEntity().apply {
            this.userId = this@UserSettingsServiceTest.userId
            this.contextBlock = existing?.toByteArray(Charsets.UTF_8)
        }
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(entity))
        return entity
    }

    private fun UserSettingsEntity.contextText(): String? = contextBlock?.toString(Charsets.UTF_8)

    @Test
    fun `appendToContextBlock adds to an empty context`() {
        val entity = stubContext(existing = null)

        val result = service.appendToContextBlock(userId, "  I prefer mornings.  ")

        assertEquals(AppendContextResult.APPENDED, result)
        assertEquals("I prefer mornings.", entity.contextText())
    }

    @Test
    fun `appendToContextBlock appends below existing content separated by a blank line`() {
        val entity = stubContext(existing = "I leave early on Fridays.")

        val result = service.appendToContextBlock(userId, "I prefer no work on Tuesday evenings.")

        assertEquals(AppendContextResult.APPENDED, result)
        assertEquals(
            "I leave early on Fridays.\n\nI prefer no work on Tuesday evenings.",
            entity.contextText(),
        )
    }

    @Test
    fun `appendToContextBlock returns FULL and writes nothing when the cap would overflow`() {
        val existing = "x".repeat(UserSettingsService.CONTEXT_BLOCK_MAX_CHARS - 5)
        val entity = stubContext(existing = existing)

        val result = service.appendToContextBlock(userId, "way past the cap")

        assertEquals(AppendContextResult.FULL, result)
        assertEquals(existing, entity.contextText())
        verify(settingsRepository, never()).save(any<UserSettingsEntity>())
    }

    @Test
    fun `appendToContextBlock is a no-op for a blank addition`() {
        val result = service.appendToContextBlock(userId, "   ")

        assertEquals(AppendContextResult.NO_OP, result)
        verify(settingsRepository, never()).save(any<UserSettingsEntity>())
        verify(settingsRepository, never()).findById(any())
    }

    // ── initializeForNewUser ────────────────────────────────────────────────────

    @Test
    fun `initializeForNewUser grants STANDARD to a claimed user when the cap has headroom`() {
        whenever(settingsRepository.countByAiTierIn(UserSettingsService.GRANTED_AI_TIER_NAMES)).thenReturn(1L)
        whenever(settingsRepository.save(any<UserSettingsEntity>())).thenAnswer { it.arguments[0] }

        serviceWith(AiProperties(tierCap = AiTierCapProperties(maxGrantedUsers = 2))).initializeForNewUser(userId, claimed = true)

        val captor = argumentCaptor<UserSettingsEntity>()
        verify(settingsRepository).save(captor.capture())
        assertTrue(captor.firstValue.aiEnabled)
        assertEquals(AiTier.STANDARD.tierName, captor.firstValue.aiTier)
    }

    @Test
    fun `initializeForNewUser falls back to the bounded DEMO tier for a claimed user once the cap is reached`() {
        whenever(settingsRepository.countByAiTierIn(UserSettingsService.GRANTED_AI_TIER_NAMES)).thenReturn(2L)
        whenever(settingsRepository.save(any<UserSettingsEntity>())).thenAnswer { it.arguments[0] }

        serviceWith(AiProperties(tierCap = AiTierCapProperties(maxGrantedUsers = 2))).initializeForNewUser(userId, claimed = true)

        val captor = argumentCaptor<UserSettingsEntity>()
        verify(settingsRepository).save(captor.capture())
        assertTrue(captor.firstValue.aiEnabled)
        assertEquals(AiTier.DEMO.tierName, captor.firstValue.aiTier)
    }

    @Test
    fun `initializeForNewUser always grants an unclaimed (demo) user the bounded DEMO tier, bypassing the cap`() {
        whenever(settingsRepository.save(any<UserSettingsEntity>())).thenAnswer { it.arguments[0] }

        serviceWith(AiProperties(tierCap = AiTierCapProperties(maxGrantedUsers = 0))).initializeForNewUser(userId, claimed = false)

        val captor = argumentCaptor<UserSettingsEntity>()
        verify(settingsRepository).save(captor.capture())
        assertTrue(captor.firstValue.aiEnabled)
        assertEquals(AiTier.DEMO.tierName, captor.firstValue.aiTier)
        verify(settingsRepository, never()).countByAiTierIn(any())
    }

    // ── upgradeToStandardOnClaim ────────────────────────────────────────────────

    @Test
    fun `upgradeToStandardOnClaim upgrades a DEMO-tier account to STANDARD while the cap has headroom`() {
        val entity = UserSettingsEntity().apply { userId = this@UserSettingsServiceTest.userId; aiTier = "demo"; aiEnabled = true }
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(entity))
        whenever(settingsRepository.countByAiTierIn(UserSettingsService.GRANTED_AI_TIER_NAMES)).thenReturn(1L)
        whenever(settingsRepository.save(any<UserSettingsEntity>())).thenAnswer { it.arguments[0] }

        serviceWith(AiProperties(tierCap = AiTierCapProperties(maxGrantedUsers = 2))).upgradeToStandardOnClaim(userId)

        assertEquals(AiTier.STANDARD.tierName, entity.aiTier)
        verify(settingsRepository).save(entity)
    }

    @Test
    fun `upgradeToStandardOnClaim keeps the existing DEMO budget once the cap is reached`() {
        val entity = UserSettingsEntity().apply { userId = this@UserSettingsServiceTest.userId; aiTier = "demo"; aiEnabled = true }
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(entity))
        whenever(settingsRepository.countByAiTierIn(UserSettingsService.GRANTED_AI_TIER_NAMES)).thenReturn(2L)

        serviceWith(AiProperties(tierCap = AiTierCapProperties(maxGrantedUsers = 2))).upgradeToStandardOnClaim(userId)

        assertEquals(AiTier.DEMO.tierName, entity.aiTier)
        verify(settingsRepository, never()).save(any<UserSettingsEntity>())
    }

    @Test
    fun `upgradeToStandardOnClaim is a no-op for a non-DEMO tier`() {
        val entity = UserSettingsEntity().apply { userId = this@UserSettingsServiceTest.userId; aiTier = "none"; aiEnabled = false }
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(entity))

        service.upgradeToStandardOnClaim(userId)

        assertEquals(AiTier.NONE.tierName, entity.aiTier)
        verify(settingsRepository, never()).countByAiTierIn(any())
        verify(settingsRepository, never()).save(any<UserSettingsEntity>())
    }

    // ── update: aiEnabled clamp ─────────────────────────────────────────────────

    @Test
    fun `update cannot self-enable AI when the tier doesn't grant access`() {
        val entity = UserSettingsEntity().apply { userId = this@UserSettingsServiceTest.userId; aiTier = "none" }
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(entity))
        whenever(settingsRepository.save(any<UserSettingsEntity>())).thenAnswer { it.arguments[0] }

        val result = service.update(userId, baseRequest().copy(aiEnabled = true))

        assertFalse(result.aiEnabled)
    }

    @Test
    fun `update allows aiEnabled when the tier grants access`() {
        val entity = UserSettingsEntity().apply { userId = this@UserSettingsServiceTest.userId; aiTier = "standard" }
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(entity))
        whenever(settingsRepository.save(any<UserSettingsEntity>())).thenAnswer { it.arguments[0] }

        val result = service.update(userId, baseRequest().copy(aiEnabled = true))

        assertTrue(result.aiEnabled)
    }
}
