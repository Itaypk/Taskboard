package dev.itayp.tasker.planning

import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.*

@ExtendWith(MockitoExtension::class)
class PlannedTaskServiceTest {

    @Mock lateinit var plannedTaskRepository: PlannedTaskRepository
    @Mock lateinit var plannedTaskSlotRepository: PlannedTaskSlotRepository

    @InjectMocks lateinit var service: PlannedTaskService

    private val sessionId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val taskId1 = UUID.randomUUID()
    private val taskId2 = UUID.randomUUID()
    private val slot = AgreedTimeSlot("2026-05-12T09:00:00Z", "2026-05-12T10:00:00Z")

    private fun savedEntity(id: UUID = UUID.randomUUID(), backlogTaskId: UUID? = null, position: Int = 0) =
        PlannedTaskEntity().apply {
            this.id = id
            this.sessionId = this@PlannedTaskServiceTest.sessionId
            this.userId = this@PlannedTaskServiceTest.userId
            this.backlogTaskId = backlogTaskId
            this.title = "title"
            this.position = position
        }

    // ── persist ──────────────────────────────────────────────────────────────

    @Test
    fun `persist inserts all tasks when session has no existing tasks`() {
        whenever(plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId)).thenReturn(emptyList())
        val saved = savedEntity(backlogTaskId = taskId1)
        whenever(plannedTaskRepository.findBySessionIdAndBacklogTaskId(sessionId, taskId1)).thenReturn(null)
        whenever(plannedTaskRepository.save(any<PlannedTaskEntity>())).thenReturn(saved)

        val tasks = listOf(AgreedPlanTask(taskId = taskId1, title = "Task A", slots = listOf(slot)))
        service.persist(sessionId, userId, tasks)

        verify(plannedTaskRepository).save(any())
        verify(plannedTaskSlotRepository).save(any())
    }

    @Test
    fun `persist deletes orphaned tracked task not in incoming list`() {
        val orphanId = UUID.randomUUID()
        val orphan = savedEntity(id = orphanId, backlogTaskId = taskId2)
        whenever(plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId)).thenReturn(listOf(orphan))
        whenever(plannedTaskRepository.findBySessionIdAndBacklogTaskId(sessionId, taskId1)).thenReturn(null)
        val saved = savedEntity(backlogTaskId = taskId1)
        whenever(plannedTaskRepository.save(any<PlannedTaskEntity>())).thenReturn(saved)

        val tasks = listOf(AgreedPlanTask(taskId = taskId1, title = "Task A", slots = listOf(slot)))
        service.persist(sessionId, userId, tasks)

        verify(plannedTaskSlotRepository).deleteAllByPlannedTaskId(orphanId)
        verify(plannedTaskRepository).delete(orphan)
    }

    @Test
    fun `persist updates slots when tracked task is re-planned`() {
        val ptId = UUID.randomUUID()
        val existing = savedEntity(id = ptId, backlogTaskId = taskId1)
        whenever(plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId)).thenReturn(listOf(existing))
        whenever(plannedTaskRepository.findBySessionIdAndBacklogTaskId(sessionId, taskId1)).thenReturn(existing)
        whenever(plannedTaskRepository.save(existing)).thenReturn(existing)

        val tasks = listOf(AgreedPlanTask(taskId = taskId1, title = "Updated", slots = listOf(slot)))
        service.persist(sessionId, userId, tasks)

        verify(plannedTaskSlotRepository).deleteAllByPlannedTaskId(ptId)
        verify(plannedTaskRepository).save(existing)
        verify(plannedTaskSlotRepository).save(any())
    }

    @Test
    fun `persist deletes and re-inserts ad-hoc tasks`() {
        val adHocId = UUID.randomUUID()
        val adHoc = savedEntity(id = adHocId, backlogTaskId = null)
        whenever(plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId)).thenReturn(listOf(adHoc))
        val newAdHoc = savedEntity(backlogTaskId = null)
        whenever(plannedTaskRepository.save(any<PlannedTaskEntity>())).thenReturn(newAdHoc)

        val tasks = listOf(AgreedPlanTask(taskId = null, title = "Ad-hoc", slots = listOf(slot)))
        service.persist(sessionId, userId, tasks)

        verify(plannedTaskSlotRepository).deleteAllByPlannedTaskId(adHocId)
        verify(plannedTaskRepository).delete(adHoc)
        verify(plannedTaskRepository).save(any())
    }

    // ── upsertSingleTask ─────────────────────────────────────────────────────

    @Test
    fun `upsertSingleTask inserts new tracked task at next available position`() {
        val existing = listOf(savedEntity(backlogTaskId = taskId2, position = 0))
        whenever(plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId)).thenReturn(existing)
        whenever(plannedTaskRepository.findBySessionIdAndBacklogTaskId(sessionId, taskId1)).thenReturn(null)
        val saved = savedEntity(backlogTaskId = taskId1, position = 1)
        whenever(plannedTaskRepository.save(any<PlannedTaskEntity>())).thenReturn(saved)

        val task = AgreedPlanTask(taskId = taskId1, title = "New Task", slots = listOf(slot))
        service.upsertSingleTask(sessionId, userId, task)

        val captor = argumentCaptor<PlannedTaskEntity>()
        verify(plannedTaskRepository).save(captor.capture())
        assert(captor.firstValue.position == 1)
    }

    @Test
    fun `upsertSingleTask updates existing tracked task and preserves position`() {
        val ptId = UUID.randomUUID()
        val existing = savedEntity(id = ptId, backlogTaskId = taskId1, position = 2)
        whenever(plannedTaskRepository.findBySessionIdAndBacklogTaskId(sessionId, taskId1)).thenReturn(existing)
        whenever(plannedTaskRepository.save(existing)).thenReturn(existing)

        val task = AgreedPlanTask(taskId = taskId1, title = "Updated", slots = listOf(slot))
        service.upsertSingleTask(sessionId, userId, task)

        verify(plannedTaskSlotRepository).deleteAllByPlannedTaskId(ptId)
        verify(plannedTaskRepository).save(existing)
        assert(existing.position == 2)
        verify(plannedTaskSlotRepository).save(any())
    }
}
