package dev.itayp.tasker.planning

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PlannedTaskService(
    private val plannedTaskRepository: PlannedTaskRepository,
    private val plannedTaskSlotRepository: PlannedTaskSlotRepository,
    private val userCrypto: UserCryptoService,
) {
    /**
     * Returns the planned tasks for [sessionId] in position order, decrypted, with their slots
     * already attached. Useful for assembling prompts or recaps from an existing plan.
     */
    @Transactional(readOnly = true)
    fun findForSession(userId: UUID, sessionId: UUID): List<AgreedPlanTask> {
        val tasks = plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId)
        if (tasks.isEmpty()) return emptyList()
        val slotsByTask = plannedTaskSlotRepository
            .findAllByPlannedTaskIdIn(tasks.mapNotNull { it.id })
            .groupBy { it.plannedTaskId }
        return tasks.mapNotNull { task ->
            // backlog_task_id is NOT NULL in the DB (changeset 2); the JPA field is nullable only for
            // no-arg construction, so this guard is just defensive — real rows always have an id.
            val backlogTaskId = task.backlogTaskId ?: return@mapNotNull null
            val slots = slotsByTask[task.id].orEmpty().map { slot ->
                AgreedTimeSlot(
                    startIso = slot.startIso ?: "",
                    endIso = slot.endIso ?: "",
                    label = slot.label,
                )
            }
            AgreedPlanTask(
                taskId = backlogTaskId,
                title = userCrypto.decrypt(userId, task.title).orEmpty(),
                notes = userCrypto.decrypt(userId, task.notes),
                slots = slots,
            )
        }
    }

    /** Looks up a single planned task's slots within [sessionId], or null if it isn't planned there. */
    @Transactional(readOnly = true)
    fun findTaskInSession(userId: UUID, sessionId: UUID, backlogTaskId: UUID): AgreedPlanTask? {
        val task = plannedTaskRepository.findBySessionIdAndBacklogTaskId(sessionId, backlogTaskId) ?: return null
        val slots = plannedTaskSlotRepository.findAllByPlannedTaskIdIn(listOf(task.id!!)).map { slot ->
            AgreedTimeSlot(startIso = slot.startIso ?: "", endIso = slot.endIso ?: "", label = slot.label)
        }
        return AgreedPlanTask(
            taskId = backlogTaskId,
            title = userCrypto.decrypt(userId, task.title).orEmpty(),
            notes = userCrypto.decrypt(userId, task.notes),
            slots = slots,
        )
    }

    @Transactional
    fun persist(sessionId: UUID, userId: UUID, tasks: List<AgreedPlanTask>) {
        val existing = plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId)
        val incomingIds = tasks.map { it.taskId }.toSet()

        existing.forEach { pt ->
            if (pt.backlogTaskId == null || pt.backlogTaskId !in incomingIds) {
                plannedTaskSlotRepository.deleteAllByPlannedTaskId(pt.id!!)
                plannedTaskRepository.delete(pt)
            }
        }

        tasks.forEachIndexed { index, task -> upsertTask(sessionId, userId, task, index) }
    }

    /**
     * Removes a single task (and its slots) from [sessionId], e.g. when it's unscheduled out of the
     * plan. No-op if the task isn't planned in that session. Notification cleanup (reminders, calendar
     * cancellations) is the caller's responsibility and must run *before* this, while the slots are
     * still readable.
     */
    @Transactional
    fun deleteTaskFromSession(sessionId: UUID, backlogTaskId: UUID) {
        val existing = plannedTaskRepository.findBySessionIdAndBacklogTaskId(sessionId, backlogTaskId) ?: return
        plannedTaskSlotRepository.deleteAllByPlannedTaskId(existing.id!!)
        plannedTaskRepository.delete(existing)
    }

    @Transactional
    fun upsertSingleTask(sessionId: UUID, userId: UUID, task: AgreedPlanTask): PlannedTaskEntity {
        val position = plannedTaskRepository.findBySessionIdAndBacklogTaskId(sessionId, task.taskId)?.position
            ?: plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId).size
        return upsertTask(sessionId, userId, task, position)
    }

    private fun upsertTask(sessionId: UUID, userId: UUID, task: AgreedPlanTask, position: Int): PlannedTaskEntity {
        val existing = plannedTaskRepository.findBySessionIdAndBacklogTaskId(sessionId, task.taskId)

        val entity = if (existing != null) {
            plannedTaskSlotRepository.deleteAllByPlannedTaskId(existing.id!!)
            existing.title = userCrypto.encrypt(userId, task.title)
            existing.notes = userCrypto.encrypt(userId, task.notes)
            existing.position = position
            plannedTaskRepository.save(existing)
        } else {
            plannedTaskRepository.save(PlannedTaskEntity().apply {
                this.sessionId = sessionId
                this.userId = userId
                this.backlogTaskId = task.taskId
                this.title = userCrypto.encrypt(userId, task.title)
                this.notes = userCrypto.encrypt(userId, task.notes)
                this.position = position
            })
        }

        task.slots.forEach { slot ->
            plannedTaskSlotRepository.save(PlannedTaskSlotEntity().apply {
                this.plannedTaskId = entity.id
                this.startIso = slot.startIso
                this.endIso = slot.endIso
                this.label = slot.label
            })
        }

        return entity
    }
}
