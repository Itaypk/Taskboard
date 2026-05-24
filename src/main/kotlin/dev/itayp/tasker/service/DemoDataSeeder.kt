package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.PlannedTaskEntity
import dev.itayp.tasker.planning.PlannedTaskRepository
import dev.itayp.tasker.planning.PlannedTaskSlotEntity
import dev.itayp.tasker.planning.PlannedTaskSlotRepository
import dev.itayp.tasker.planning.PlanningSessionEntity
import dev.itayp.tasker.planning.PlanningSessionRepository
import dev.itayp.tasker.planning.PlanningSessionStatus
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class DemoDataSeeder(
    private val categoryRepository: BacklogTaskCategoryRepository,
    private val taskRepository: BacklogTaskRepository,
    private val planningSessionRepository: PlanningSessionRepository,
    private val plannedTaskRepository: PlannedTaskRepository,
    private val plannedTaskSlotRepository: PlannedTaskSlotRepository,
    private val clock: Clock,
) {

    @Transactional
    fun seed(userId: UUID) {
        val categories = categoryRepository.findAllByUserId(userId).associateBy { it.label }
        val now = clock.instant()
        val today = LocalDate.now(clock)

        fun task(
            title: String,
            categoryLabel: String,
            priority: TaskPriority,
            status: TaskStatus = TaskStatus.TODO,
            description: String? = null,
            estimatedMinutes: Int? = null,
            deadline: LocalDate? = null,
        ): BacklogTaskEntity {
            val category = categories[categoryLabel] ?: return BacklogTaskEntity()
            return BacklogTaskEntity().apply {
                this.userId = userId
                this.title = title
                this.description = description
                this.priority = priority
                this.status = status
                this.estimatedMinutes = estimatedMinutes
                this.deadline = deadline
                this.category = category
                this.createdAt = now
            }
        }

        val teamUpdate  = task("Prepare weekly team update", "Work", TaskPriority.HIGH, estimatedMinutes = 30)
        val pullReviews = task("Review open pull requests", "Work", TaskPriority.MEDIUM)
        val retroNotes  = task("Write Q2 retrospective notes", "Work", TaskPriority.LOW)
        val dentist     = task("Book dentist appointment", "Personal", TaskPriority.HIGH, deadline = today.plusWeeks(2))
        val book        = task("Read that book I keep putting off", "Personal", TaskPriority.LOW)
        val tap         = task("Fix the dripping tap in the kitchen", "Home", TaskPriority.MEDIUM)
        val bulbs       = task("Order replacement light bulbs", "Home", TaskPriority.LOW, status = TaskStatus.DONE)
        val run         = task("Go for a 30-min run", "Health", TaskPriority.MEDIUM, estimatedMinutes = 35)

        val taskList = listOf(teamUpdate, pullReviews, retroNotes, dentist, book, tap, bulbs, run)
            .filter { it.userId != null }

        val sortKeys = SortKeyGenerator.spreadKeys(taskList.size)
        taskList.zip(sortKeys).forEach { (t, key) -> t.sortKey = key }
        val saved = taskRepository.saveAll(taskList)

        // Create an active planning session for the current week
        val weekStart = today.with(DayOfWeek.MONDAY)
        val session = planningSessionRepository.save(PlanningSessionEntity().apply {
            this.userId = userId
            this.status = PlanningSessionStatus.ACTIVE
            this.startedAt = now
            this.weekStart = weekStart
            this.summary = null
        })
        val sessionId = session.id!!

        // Schedule the first 3 meaningful tasks into the plan
        val plannedTasks = listOf(
            saved.find { it.title == teamUpdate.title },
            saved.find { it.title == pullReviews.title },
            saved.find { it.title == run.title },
        ).filterNotNull()

        plannedTasks.forEach { t -> t.lastScheduledInSessionId = sessionId }
        taskRepository.saveAll(plannedTasks)

        // Create planned task entries with time slots
        val monday    = weekStart
        val tuesday   = weekStart.plusDays(1)
        val wednesday = weekStart.plusDays(2)

        data class SlotSpec(val date: LocalDate, val startHour: Int, val endHour: Int, val endMinute: Int = 0)

        val slotSpecs = listOf(
            SlotSpec(monday,    9,  9, 30),
            SlotSpec(tuesday,  10, 11,  0),
            SlotSpec(wednesday, 7,  7, 35),
        )

        plannedTasks.zip(slotSpecs).forEachIndexed { index, (backlogTask, slot) ->
            val pt = plannedTaskRepository.save(PlannedTaskEntity().apply {
                this.sessionId = sessionId
                this.userId = userId
                this.backlogTaskId = backlogTask.id
                this.title = backlogTask.title!!
                this.position = index
            })
            val startInstant = slot.date.atTime(LocalTime.of(slot.startHour, 0)).toInstant(ZoneOffset.UTC)
            val endInstant   = slot.date.atTime(LocalTime.of(slot.endHour, slot.endMinute)).toInstant(ZoneOffset.UTC)
            plannedTaskSlotRepository.save(PlannedTaskSlotEntity().apply {
                this.plannedTaskId = pt.id
                this.startIso = startInstant.toString()
                this.endIso   = endInstant.toString()
            })
        }
    }
}
