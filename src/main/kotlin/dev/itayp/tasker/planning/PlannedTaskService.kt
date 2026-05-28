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
        return tasks.map { task ->
            val slots = slotsByTask[task.id].orEmpty().map { slot ->
                AgreedTimeSlot(
                    startIso = slot.startIso ?: "",
                    endIso = slot.endIso ?: "",
                    label = slot.label,
                )
            }
            AgreedPlanTask(
                taskId = task.backlogTaskId,
                title = userCrypto.decrypt(userId, task.title).orEmpty(),
                notes = userCrypto.decrypt(userId, task.notes),
                slots = slots,
            )
        }
    }

    @Transactional
    fun persist(sessionId: UUID, userId: UUID, tasks: List<AgreedPlanTask>) {
        val existing = plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId)
        val incomingIds = tasks.mapNotNull { it.taskId }.toSet()

        existing.forEach { pt ->
            if (pt.backlogTaskId == null || pt.backlogTaskId !in incomingIds) {
                plannedTaskSlotRepository.deleteAllByPlannedTaskId(pt.id!!)
                plannedTaskRepository.delete(pt)
            }
        }

        tasks.forEachIndexed { index, task -> upsertTask(sessionId, userId, task, index) }
    }

    @Transactional
    fun upsertSingleTask(sessionId: UUID, userId: UUID, task: AgreedPlanTask): PlannedTaskEntity {
        val position = if (task.taskId != null) {
            plannedTaskRepository.findBySessionIdAndBacklogTaskId(sessionId, task.taskId)?.position
                ?: plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId).size
        } else {
            plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId).size
        }
        return upsertTask(sessionId, userId, task, position)
    }

    private fun upsertTask(sessionId: UUID, userId: UUID, task: AgreedPlanTask, position: Int): PlannedTaskEntity {
        val existing = task.taskId?.let { plannedTaskRepository.findBySessionIdAndBacklogTaskId(sessionId, it) }

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
