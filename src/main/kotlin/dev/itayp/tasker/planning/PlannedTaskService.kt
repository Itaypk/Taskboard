package dev.itayp.tasker.planning

import dev.itayp.tasker.planning.dto.AgreedPlanTask
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PlannedTaskService(
    private val plannedTaskRepository: PlannedTaskRepository,
    private val plannedTaskSlotRepository: PlannedTaskSlotRepository,
) {
    @Transactional
    fun persist(sessionId: UUID, userId: UUID, tasks: List<AgreedPlanTask>) {
        tasks.forEachIndexed { index, agreedTask ->
            val taskEntity = plannedTaskRepository.save(PlannedTaskEntity().apply {
                this.sessionId = sessionId
                this.userId = userId
                this.backlogTaskId = agreedTask.taskId
                this.title = agreedTask.title
                this.notes = agreedTask.notes
                this.position = index
            })
            agreedTask.slots.forEach { slot ->
                plannedTaskSlotRepository.save(PlannedTaskSlotEntity().apply {
                    this.plannedTaskId = taskEntity.id
                    this.startIso = slot.startIso
                    this.endIso = slot.endIso
                    this.label = slot.label
                })
            }
        }
    }
}
