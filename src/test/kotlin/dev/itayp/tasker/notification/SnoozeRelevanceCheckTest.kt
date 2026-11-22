package dev.itayp.tasker.notification

import dev.itayp.tasker.model.BacklogTask
import dev.itayp.tasker.model.BacklogTaskCategory
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.PlannedTaskEntity
import dev.itayp.tasker.planning.PlannedTaskRepository
import dev.itayp.tasker.planning.PlannedTaskSlotEntity
import dev.itayp.tasker.planning.PlannedTaskSlotRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@ExtendWith(MockitoExtension::class)
class SnoozeRelevanceCheckTest {

    @Mock lateinit var plannedTaskRepository: PlannedTaskRepository
    @Mock lateinit var plannedTaskSlotRepository: PlannedTaskSlotRepository

    private val check by lazy { SnoozeRelevanceCheck(plannedTaskRepository, plannedTaskSlotRepository) }

    private val userId: UUID = UUID.randomUUID()
    private val taskId: UUID = UUID.randomUUID()
    private val sessionId: UUID = UUID.randomUUID()
    private val nextWeekSessionId: UUID = UUID.randomUUID()
    private val snoozedStart = "2026-05-13T10:00:00Z"

    private val notification = ScheduledNotificationEntity().apply {
        id = UUID.randomUUID()
        userId = this@SnoozeRelevanceCheckTest.userId
        sessionId = this@SnoozeRelevanceCheckTest.sessionId
        backlogTaskId = taskId
        slotStartIso = snoozedStart
        slotEndIso = "2026-05-13T11:00:00Z"
        status = NotificationStatus.PENDING
        snoozed = true
    }

    private fun task(status: TaskStatus = TaskStatus.TODO): BacklogTask {
        val boardId = UUID.randomUUID()
        return BacklogTask(
            id = taskId, boardId = boardId, assigneeUserId = null, title = "Buy milk", description = null,
            url = null, priority = null, deadline = null, estimatedMinutes = null, status = status,
            category = BacklogTaskCategory(UUID.randomUUID(), boardId, "General", CategoryColor.SKY),
            tags = emptySet(), sortKey = "a", createdAt = Instant.parse("2026-05-13T08:00:00Z"),
            updatedAt = null, rescheduleCount = 0, lastScheduledInSessionId = null, relevantFrom = null,
        )
    }

    /** Stubs the user's placements of the task: each session id mapped to its slot starts. */
    private fun planned(vararg placements: Pair<UUID, List<String>>) {
        val entities = placements.map { (session, _) ->
            PlannedTaskEntity().apply {
                id = UUID.randomUUID()
                this.sessionId = session
                this.userId = this@SnoozeRelevanceCheckTest.userId
                backlogTaskId = taskId
            }
        }
        val slots = entities.zip(placements).flatMap { (entity, placement) ->
            placement.second.map { start ->
                PlannedTaskSlotEntity().apply {
                    id = UUID.randomUUID()
                    plannedTaskId = entity.id
                    startIso = start
                    endIso = start
                }
            }
        }
        whenever(plannedTaskRepository.findAllByUserIdAndBacklogTaskIdIn(userId, listOf(taskId))).thenReturn(entities)
        whenever(plannedTaskSlotRepository.findAllByPlannedTaskIdIn(any())).thenReturn(slots)
    }

    @Test
    fun `still current when the slot is in its plan and nothing later exists`() {
        planned(sessionId to listOf(snoozedStart, "2026-05-15T10:00:00Z"))

        assertNull(check.staleReason(notification, task()))
    }

    @Test
    fun `stale once the task is rescheduled into a later plan`() {
        planned(sessionId to listOf(snoozedStart), nextWeekSessionId to listOf("2026-05-22T15:00:00Z"))

        assertEquals("task rescheduled in a later plan", check.staleReason(notification, task()))
    }

    @Test
    fun `an earlier placement in another plan does not make it stale`() {
        planned(sessionId to listOf(snoozedStart), nextWeekSessionId to listOf("2026-05-06T15:00:00Z"))

        assertNull(check.staleReason(notification, task()))
    }

    @Test
    fun `stale when the slot was moved within its plan`() {
        planned(sessionId to listOf("2026-05-14T10:00:00Z"))

        assertEquals("slot removed from its plan", check.staleReason(notification, task()))
    }

    @Test
    fun `stale when the task was dropped from its plan`() {
        planned(nextWeekSessionId to listOf("2026-05-22T15:00:00Z"))

        assertEquals("task removed from its plan", check.staleReason(notification, task()))
    }

    @Test
    fun `stale when the task is done or archived`() {
        assertEquals("task is DONE", check.staleReason(notification, task(TaskStatus.DONE)))
        assertEquals("task is ARCHIVED", check.staleReason(notification, task(TaskStatus.ARCHIVED)))
    }
}
