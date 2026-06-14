package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.BacklogTaskChangeEventEntity
import dev.itayp.tasker.planning.BacklogTaskChangeEventRepository
import dev.itayp.tasker.planning.BacklogTaskChangeType
import dev.itayp.tasker.planning.PlanningSessionRepository
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.UserRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class StatsServiceTest {

    @Mock private lateinit var userRepository: UserRepository
    @Mock private lateinit var taskRepository: BacklogTaskRepository
    @Mock private lateinit var sessionRepository: PlanningSessionRepository
    @Mock private lateinit var changeEventRepository: BacklogTaskChangeEventRepository
    @Mock private lateinit var boardMembershipService: BoardMembershipService

    private val now = Instant.parse("2026-06-05T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val service: StatsService by lazy {
        StatsService(userRepository, taskRepository, sessionRepository, changeEventRepository, boardMembershipService, clock)
    }

    private val userId = UUID.randomUUID()
    private val boardId = UUID.randomUUID()

    @Test
    fun `empty user yields an empty snapshot`() {
        stubUser(joinedAt = now)
        stubCounts(open = 0, completed = 0, sessions = 0)
        whenever(changeEventRepository.findAllByActorUserIdOrderByOccurredAtAsc(userId)).thenReturn(emptyList())

        val stats = service.computeStats(userId)

        assertTrue(stats.isEmpty)
        assertNull(stats.avgCompletion)
        assertEquals(0.0, stats.avgTasksCreatedPerWeek)
        assertEquals(0, stats.totalTasksCreated)
        assertEquals(0, stats.totalTasksCompleted)
    }

    @Test
    fun `derives throughput and completion time from the change-event log`() {
        stubUser(joinedAt = now.minus(Duration.ofDays(28)))
        stubCounts(open = 3, completed = 2, sessions = 4)

        val taskA = UUID.randomUUID()
        val taskB = UUID.randomUUID()
        val start = now.minus(Duration.ofDays(28))
        val events = listOf(
            created(taskA, start),
            created(taskB, start.plus(Duration.ofDays(1))),
            // taskA completed two days after creation
            statusChange(taskA, start.plus(Duration.ofDays(2)), TaskStatus.TODO, TaskStatus.DONE),
            // taskB completed four days after creation
            statusChange(taskB, start.plus(Duration.ofDays(5)), TaskStatus.TODO, TaskStatus.DONE),
        )
        whenever(changeEventRepository.findAllByActorUserIdOrderByOccurredAtAsc(userId)).thenReturn(events)

        val stats = service.computeStats(userId)

        assertEquals(3, stats.openTasks)
        assertEquals(2, stats.completedTasks)
        assertEquals(4, stats.planningSessions)
        // 2 created over a 4-week span = 0.5/week
        assertEquals(0.5, stats.avgTasksCreatedPerWeek)
        assertEquals(0.5, stats.avgTasksCompletedPerWeek)
        // mean of 2 days and 4 days = 3 days
        assertEquals(Duration.ofDays(3), stats.avgCompletion)
        // lifetime totals come straight off the change log: 2 created, 2 distinct completed
        assertEquals(2, stats.totalTasksCreated)
        assertEquals(2, stats.totalTasksCompleted)
    }

    @Test
    fun `re-completed task counts once and uses its first completion`() {
        stubUser(joinedAt = now.minus(Duration.ofDays(7)))
        stubCounts(open = 1, completed = 1, sessions = 1)

        val task = UUID.randomUUID()
        val start = now.minus(Duration.ofDays(7))
        val events = listOf(
            created(task, start),
            statusChange(task, start.plus(Duration.ofDays(1)), TaskStatus.TODO, TaskStatus.DONE),
            statusChange(task, start.plus(Duration.ofDays(2)), TaskStatus.DONE, TaskStatus.TODO),
            statusChange(task, start.plus(Duration.ofDays(3)), TaskStatus.TODO, TaskStatus.DONE),
        )
        whenever(changeEventRepository.findAllByActorUserIdOrderByOccurredAtAsc(userId)).thenReturn(events)

        val stats = service.computeStats(userId)

        // one distinct task created and completed over a one-week span
        assertEquals(1.0, stats.avgTasksCreatedPerWeek)
        assertEquals(1.0, stats.avgTasksCompletedPerWeek)
        // first completion was one day after creation
        assertEquals(Duration.ofDays(1), stats.avgCompletion)
        // a re-completed task still counts once toward the lifetime totals
        assertEquals(1, stats.totalTasksCreated)
        assertEquals(1, stats.totalTasksCompleted)
    }

    private fun stubUser(joinedAt: Instant) {
        val user = UserEntity().apply {
            id = userId
            createdAt = joinedAt
        }
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
    }

    private fun stubCounts(open: Long, completed: Long, sessions: Long) {
        whenever(boardMembershipService.listBoardIds(userId)).thenReturn(listOf(boardId))
        whenever(taskRepository.countByBoardIdAndStatus(boardId, TaskStatus.TODO)).thenReturn(open)
        whenever(taskRepository.countByBoardIdAndStatus(boardId, TaskStatus.DONE)).thenReturn(completed)
        whenever(sessionRepository.countByUserIdAndStatus(userId, PlanningSessionStatus.COMPLETED)).thenReturn(sessions)
    }

    private fun created(taskId: UUID, at: Instant) = BacklogTaskChangeEventEntity().apply {
        this.actorUserId = this@StatsServiceTest.userId
        this.taskId = taskId
        this.changeType = BacklogTaskChangeType.CREATED
        this.newStatus = TaskStatus.TODO
        this.occurredAt = at
    }

    private fun statusChange(taskId: UUID, at: Instant, from: TaskStatus, to: TaskStatus) =
        BacklogTaskChangeEventEntity().apply {
            this.actorUserId = this@StatsServiceTest.userId
            this.taskId = taskId
            this.changeType = BacklogTaskChangeType.STATUS_CHANGED
            this.previousStatus = from
            this.newStatus = to
            this.occurredAt = at
        }
}
