package dev.itayp.tasker.planning

import dev.itayp.tasker.repository.BacklogTaskRepository
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
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@ExtendWith(MockitoExtension::class)
class PlanningSessionServiceTest {

    @Mock lateinit var planningSessionRepository: PlanningSessionRepository
    @Mock lateinit var backlogTaskChangeService: BacklogTaskChangeService
    @Mock lateinit var backlogTaskRepository: BacklogTaskRepository

    private val now = Instant.parse("2026-05-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val service by lazy {
        PlanningSessionService(planningSessionRepository, backlogTaskChangeService, backlogTaskRepository, clock)
    }

    private val userId = UUID.randomUUID()

    @Test
    fun `startSession returns existing active session if one already exists`() {
        val existing = PlanningSessionEntity().apply {
            this.id = UUID.randomUUID()
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = now.minusSeconds(60)
        }
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.ACTIVE)).thenReturn(existing)

        val result = service.startSession(userId)

        assertSame(existing, result)
        verify(planningSessionRepository, never()).save(any())
    }

    @Test
    fun `startSession persists a new active session when none exists`() {
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.ACTIVE)).thenReturn(null)
        whenever(planningSessionRepository.save(any<PlanningSessionEntity>())).thenAnswer { it.arguments[0] }
        val convId = UUID.randomUUID()

        service.startSession(userId, conversationId = convId)

        val captor = argumentCaptor<PlanningSessionEntity>()
        verify(planningSessionRepository).save(captor.capture())
        assertEquals(userId, captor.firstValue.userId)
        assertEquals(convId, captor.firstValue.conversationId)
        assertEquals(PlanningSessionStatus.ACTIVE, captor.firstValue.status)
        assertEquals(now, captor.firstValue.startedAt)
    }

    @Test
    fun `completeSession sets summary and ended_at`() {
        val sessionId = UUID.randomUUID()
        val entity = PlanningSessionEntity().apply {
            this.id = sessionId
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = now.minusSeconds(600)
        }
        whenever(planningSessionRepository.findByIdAndUserId(sessionId, userId)).thenReturn(entity)
        whenever(planningSessionRepository.save(any<PlanningSessionEntity>())).thenAnswer { it.arguments[0] }

        service.completeSession(userId, sessionId, "we agreed on tasks A, B")

        assertEquals(PlanningSessionStatus.COMPLETED, entity.status)
        assertEquals(now, entity.endedAt)
        assertEquals("we agreed on tasks A, B", entity.summary)
    }

    @Test
    fun `abandonSession transitions status without writing summary`() {
        val sessionId = UUID.randomUUID()
        val entity = PlanningSessionEntity().apply {
            this.id = sessionId
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = now.minusSeconds(60)
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
    fun `startSession increments reschedule count for tasks carried from previous completed session`() {
        val previousId = UUID.randomUUID()
        val previous = PlanningSessionEntity().apply {
            this.id = previousId
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.COMPLETED
            this.startedAt = now.minusSeconds(8 * 24 * 3600)
            this.endedAt = now.minusSeconds(7 * 24 * 3600)
        }
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.ACTIVE)).thenReturn(null)
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.COMPLETED)).thenReturn(previous)
        whenever(planningSessionRepository.save(any<PlanningSessionEntity>())).thenAnswer { it.arguments[0] }

        service.startSession(userId)

        verify(backlogTaskRepository).incrementRescheduleCountForUnfinishedTasks(eq(userId), eq(previousId))
    }

    @Test
    fun `startSession does not touch reschedule counts when no prior completed session exists`() {
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.ACTIVE)).thenReturn(null)
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.COMPLETED)).thenReturn(null)
        whenever(planningSessionRepository.save(any<PlanningSessionEntity>())).thenAnswer { it.arguments[0] }

        service.startSession(userId)

        verify(backlogTaskRepository, never()).incrementRescheduleCountForUnfinishedTasks(any(), any())
    }

    @Test
    fun `startSession does not touch reschedule counts when there is already an active session`() {
        val existing = PlanningSessionEntity().apply {
            this.id = UUID.randomUUID()
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = now.minusSeconds(60)
        }
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.ACTIVE)).thenReturn(existing)

        service.startSession(userId)

        verify(backlogTaskRepository, never()).incrementRescheduleCountForUnfinishedTasks(any(), any())
    }

    @Test
    fun `findCurrentPlan prefers active session`() {
        val active = PlanningSessionEntity().apply {
            this.id = UUID.randomUUID()
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = now.minusSeconds(60)
        }
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.ACTIVE)).thenReturn(active)

        assertSame(active, service.findCurrentPlan(userId))
    }

    @Test
    fun `findCurrentPlan falls back to most recent completed session`() {
        val completed = PlanningSessionEntity().apply {
            this.id = UUID.randomUUID()
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.COMPLETED
            this.startedAt = now.minusSeconds(7 * 24 * 3600)
            this.endedAt = now.minusSeconds(6 * 24 * 3600)
        }
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.ACTIVE)).thenReturn(null)
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.COMPLETED)).thenReturn(completed)

        assertSame(completed, service.findCurrentPlan(userId))
    }

    @Test
    fun `findCurrentPlan returns null when neither active nor completed exists`() {
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.ACTIVE)).thenReturn(null)
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.COMPLETED)).thenReturn(null)

        assertEquals(null, service.findCurrentPlan(userId))
    }

    @Test
    fun `diffSincePreviousSession returns empty when no prior completed session`() {
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.COMPLETED)).thenReturn(null)

        val summary = service.diffSincePreviousSession(userId)

        assertEquals(0, summary.totalEvents)
        verify(backlogTaskChangeService, never()).summarizeSince(any(), any())
    }

    @Test
    fun `diffSincePreviousSession delegates to change service using previous endedAt`() {
        val previous = PlanningSessionEntity().apply {
            this.id = UUID.randomUUID()
            this.userId = this@PlanningSessionServiceTest.userId
            this.status = PlanningSessionStatus.COMPLETED
            this.startedAt = now.minusSeconds(8 * 24 * 3600)
            this.endedAt = now.minusSeconds(7 * 24 * 3600)
        }
        whenever(planningSessionRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(
            userId, PlanningSessionStatus.COMPLETED)).thenReturn(previous)
        val expected = TaskChangeSummary(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 7)
        whenever(backlogTaskChangeService.summarizeSince(userId, previous.endedAt!!)).thenReturn(expected)

        val result = service.diffSincePreviousSession(userId)

        assertSame(expected, result)
    }
}
