package dev.itayp.tasker.planning

import dev.itayp.tasker.crypto.noopUserCryptoService
import dev.itayp.tasker.model.UserSettings
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.service.UserSettingsService
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@ExtendWith(MockitoExtension::class)
class PlanningSessionServiceTest {

    @Mock lateinit var planningSessionRepository: PlanningSessionRepository
    @Mock lateinit var backlogTaskChangeService: BacklogTaskChangeService
    @Mock lateinit var backlogTaskRepository: BacklogTaskRepository
    @Mock lateinit var userSettingsService: UserSettingsService

    // 2026-05-01 is a Friday; the Monday-anchored week containing it starts 2026-04-27 (== weekStart).
    private val now = Instant.parse("2026-05-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val crypto = noopUserCryptoService()

    private val service by lazy {
        PlanningSessionService(
            planningSessionRepository,
            backlogTaskChangeService,
            backlogTaskRepository,
            crypto,
            userSettingsService,
            clock,
        )
    }

    private val userId = UUID.randomUUID()
    private val weekStart = LocalDate.parse("2026-04-27")

    private fun settings() = UserSettings(
        userId = userId,
        displayName = null,
        contextBlock = null,
        timeZone = "UTC",
        preferredLanguage = "en",
        calendarInviteEmail = false,
        gender = null,
        agentDescription = null,
        planningCron = null,
        weekStartDay = "MONDAY",
        autoArchiveDays = null,
    )

    @Test
    fun `startSession returns existing active session if one already exists for the same week`() {
        val existingId = UUID.randomUUID()
        val existing = PlanningSessionEntity().apply {
            this.id = existingId
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = now.minusSeconds(60)
            this.weekStart = this@PlanningSessionServiceTest.weekStart
        }
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.ACTIVE)).thenReturn(existing)

        val result = service.startSession(userId, weekStart)

        assertEquals(existingId, result.id)
        verify(planningSessionRepository, never()).save(any<PlanningSessionEntity>())
    }

    @Test
    fun `startSession creates a fresh session when active session is for a different week`() {
        val existing = PlanningSessionEntity().apply {
            this.id = UUID.randomUUID()
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = now.minusSeconds(60)
            this.weekStart = LocalDate.parse("2026-04-20")
        }
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.ACTIVE)).thenReturn(existing)
        whenever(planningSessionRepository.save(any<PlanningSessionEntity>())).thenAnswer {
            (it.arguments[0] as PlanningSessionEntity).apply { id = id ?: UUID.randomUUID() }
        }

        service.startSession(userId, weekStart)

        val captor = argumentCaptor<PlanningSessionEntity>()
        verify(planningSessionRepository).save(captor.capture())
        assertEquals(weekStart, captor.firstValue.weekStart)
    }

    @Test
    fun `startSession persists a new active session when none exists`() {
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.ACTIVE)).thenReturn(null)
        whenever(planningSessionRepository.save(any<PlanningSessionEntity>())).thenAnswer {
            (it.arguments[0] as PlanningSessionEntity).apply { id = id ?: UUID.randomUUID() }
        }
        val convId = UUID.randomUUID()

        service.startSession(userId, weekStart, conversationId = convId)

        val captor = argumentCaptor<PlanningSessionEntity>()
        verify(planningSessionRepository).save(captor.capture())
        assertEquals(userId, captor.firstValue.userId)
        assertEquals(convId, captor.firstValue.conversationId)
        assertEquals(PlanningSessionStatus.ACTIVE, captor.firstValue.status)
        assertEquals(now, captor.firstValue.startedAt)
        assertEquals(weekStart, captor.firstValue.weekStart)
    }

    @Test
    fun `completeSession sets summary and ended_at`() {
        val sessionId = UUID.randomUUID()
        val entity = PlanningSessionEntity().apply {
            this.id = sessionId
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = now.minusSeconds(600)
            this.weekStart = this@PlanningSessionServiceTest.weekStart
        }
        whenever(planningSessionRepository.findByIdAndUserId(sessionId, userId)).thenReturn(entity)
        whenever(planningSessionRepository.save(any<PlanningSessionEntity>())).thenAnswer { it.arguments[0] }

        val result = service.completeSession(userId, sessionId, "we agreed on tasks A, B")

        assertEquals(PlanningSessionStatus.COMPLETED, entity.status)
        assertEquals(now, entity.endedAt)
        assertEquals("we agreed on tasks A, B", result.summary)
    }

    @Test
    fun `abandonSession transitions status without writing summary`() {
        val sessionId = UUID.randomUUID()
        val entity = PlanningSessionEntity().apply {
            this.id = sessionId
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = now.minusSeconds(60)
            this.weekStart = this@PlanningSessionServiceTest.weekStart
            this.summary = null
        }
        whenever(planningSessionRepository.findByIdAndUserId(sessionId, userId)).thenReturn(entity)
        whenever(planningSessionRepository.save(any<PlanningSessionEntity>())).thenAnswer { it.arguments[0] }

        service.abandonSession(userId, sessionId)

        assertEquals(PlanningSessionStatus.ABANDONED, entity.status)
        assertNotNull(entity.endedAt)
    }

    @Test
    fun `completeSession throws when session not found`() {
        val sessionId = UUID.randomUUID()
        whenever(planningSessionRepository.findByIdAndUserId(sessionId, userId)).thenReturn(null)

        assertFailsWith<NoSuchElementException> {
            service.completeSession(userId, sessionId, "summary")
        }
    }

    @Test
    fun `bumpRescheduleCountsForCarriedOverTasks increments count for tasks from the prior week's completed plan`() {
        val finalizingWeek = LocalDate.parse("2026-05-04")
        val previousId = UUID.randomUUID()
        val previous = PlanningSessionEntity().apply {
            this.id = previousId
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.COMPLETED
            this.startedAt = now.minusSeconds(8 * 24 * 3600)
            this.endedAt = now.minusSeconds(7 * 24 * 3600)
            this.weekStart = this@PlanningSessionServiceTest.weekStart
        }
        whenever(planningSessionRepository.findFirstByUserIdAndStatusAndWeekStartLessThanOrderByWeekStartDesc(
            userId, PlanningSessionStatus.COMPLETED, finalizingWeek)).thenReturn(previous)

        service.bumpRescheduleCountsForCarriedOverTasks(userId, finalizingWeek)

        verify(backlogTaskRepository).incrementRescheduleCountForUnfinishedTasks(eq(userId), eq(previousId))
    }

    @Test
    fun `bumpRescheduleCountsForCarriedOverTasks does nothing when no earlier completed plan exists`() {
        val finalizingWeek = LocalDate.parse("2026-05-04")
        whenever(planningSessionRepository.findFirstByUserIdAndStatusAndWeekStartLessThanOrderByWeekStartDesc(
            userId, PlanningSessionStatus.COMPLETED, finalizingWeek)).thenReturn(null)

        service.bumpRescheduleCountsForCarriedOverTasks(userId, finalizingWeek)

        verify(backlogTaskRepository, never()).incrementRescheduleCountForUnfinishedTasks(any(), any())
    }

    @Test
    fun `startSession does not bump reschedule counts at session start`() {
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.ACTIVE)).thenReturn(null)
        whenever(planningSessionRepository.save(any<PlanningSessionEntity>())).thenAnswer {
            (it.arguments[0] as PlanningSessionEntity).apply { id = id ?: UUID.randomUUID() }
        }

        service.startSession(userId, weekStart)

        verify(backlogTaskRepository, never()).incrementRescheduleCountForUnfinishedTasks(any(), any())
    }

    @Test
    fun `startSession does not touch reschedule counts when there is already an active session for the same week`() {
        val existing = PlanningSessionEntity().apply {
            this.id = UUID.randomUUID()
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = now.minusSeconds(60)
            this.weekStart = this@PlanningSessionServiceTest.weekStart
        }
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.ACTIVE)).thenReturn(existing)

        service.startSession(userId, weekStart)

        verify(backlogTaskRepository, never()).incrementRescheduleCountForUnfinishedTasks(any(), any())
    }

    @Test
    fun `findCurrentPlan returns the completed plan for the week containing today`() {
        val completedId = UUID.randomUUID()
        val completed = PlanningSessionEntity().apply {
            this.id = completedId
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.COMPLETED
            this.startedAt = now.minusSeconds(2 * 24 * 3600)
            this.endedAt = now.minusSeconds(1 * 24 * 3600)
            this.weekStart = this@PlanningSessionServiceTest.weekStart
        }
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings())
        // The week containing 'now' (2026-05-01) starts 2026-04-27 == weekStart.
        whenever(planningSessionRepository.findFirstByUserIdAndWeekStartAndStatusOrderByStartedAtDesc(
            userId, weekStart, PlanningSessionStatus.COMPLETED)).thenReturn(completed)

        val result = service.findCurrentPlan(userId)
        assertEquals(completedId, result?.id)
        assertEquals(PlanningSessionStatus.COMPLETED, result?.status)
    }

    @Test
    fun `findCurrentPlan returns null when the current week has no completed plan`() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings())
        whenever(planningSessionRepository.findFirstByUserIdAndWeekStartAndStatusOrderByStartedAtDesc(
            userId, weekStart, PlanningSessionStatus.COMPLETED)).thenReturn(null)

        assertEquals(null, service.findCurrentPlan(userId))
    }

    @Test
    fun `findPlanForWeek looks up the completed plan for the given week`() {
        val targetWeek = LocalDate.parse("2026-06-01")
        val planId = UUID.randomUUID()
        val plan = PlanningSessionEntity().apply {
            this.id = planId
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.COMPLETED
            this.startedAt = now
            this.weekStart = targetWeek
        }
        whenever(planningSessionRepository.findFirstByUserIdAndWeekStartAndStatusOrderByStartedAtDesc(
            userId, targetWeek, PlanningSessionStatus.COMPLETED)).thenReturn(plan)

        assertEquals(planId, service.findPlanForWeek(userId, targetWeek)?.id)
    }

    @Test
    fun `diffSincePreviousSession returns empty when no earlier completed plan`() {
        whenever(planningSessionRepository.findFirstByUserIdAndStatusAndWeekStartLessThanOrderByWeekStartDesc(
            userId, PlanningSessionStatus.COMPLETED, weekStart)).thenReturn(null)

        val summary = service.diffSincePreviousSession(userId, weekStart)

        assertEquals(0, summary.totalEvents)
        verify(backlogTaskChangeService, never()).summarizeSince(any(), any())
    }

    @Test
    fun `diffSincePreviousSession delegates to change service using the prior week plan's endedAt`() {
        val previous = PlanningSessionEntity().apply {
            this.id = UUID.randomUUID()
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.COMPLETED
            this.startedAt = now.minusSeconds(8 * 24 * 3600)
            this.endedAt = now.minusSeconds(7 * 24 * 3600)
            this.weekStart = LocalDate.parse("2026-04-20")
        }
        whenever(planningSessionRepository.findFirstByUserIdAndStatusAndWeekStartLessThanOrderByWeekStartDesc(
            userId, PlanningSessionStatus.COMPLETED, weekStart)).thenReturn(previous)
        val expected = TaskChangeSummary(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 7)
        whenever(backlogTaskChangeService.summarizeSince(userId, previous.endedAt!!)).thenReturn(expected)

        val result = service.diffSincePreviousSession(userId, weekStart)

        assertEquals(expected, result)
    }
}
