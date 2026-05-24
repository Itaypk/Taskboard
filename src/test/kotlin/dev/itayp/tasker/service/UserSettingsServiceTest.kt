package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.noopUserCryptoService
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.request.UpdateUserSettingsRequest
import dev.itayp.tasker.repository.UserSettingsRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.util.Optional
import java.util.UUID
import kotlin.test.assertFailsWith

@ExtendWith(MockitoExtension::class)
class UserSettingsServiceTest {

    @Mock lateinit var settingsRepository: UserSettingsRepository
    @Mock lateinit var eventPublisher: ApplicationEventPublisher

    private val service by lazy {
        UserSettingsService(settingsRepository, eventPublisher, noopUserCryptoService())
    }
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
}
