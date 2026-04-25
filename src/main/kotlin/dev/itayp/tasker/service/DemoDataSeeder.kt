package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class DemoDataSeeder(
    private val categoryRepository: BacklogTaskCategoryRepository,
    private val taskRepository: BacklogTaskRepository,
    private val clock: Clock,
) {

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

        val taskList = listOf(
            task("Prepare weekly team update", "Work", TaskPriority.HIGH, estimatedMinutes = 30),
            task("Review open pull requests", "Work", TaskPriority.MEDIUM),
            task("Write Q2 retrospective notes", "Work", TaskPriority.LOW),
            task("Book dentist appointment", "Personal", TaskPriority.HIGH, deadline = today.plusWeeks(2)),
            task("Read that book I keep putting off", "Personal", TaskPriority.LOW),
            task("Fix the dripping tap in the kitchen", "Home", TaskPriority.MEDIUM),
            task("Order replacement light bulbs", "Home", TaskPriority.LOW, status = TaskStatus.DONE),
            task("Go for a 30-min run", "Health", TaskPriority.MEDIUM, estimatedMinutes = 35),
        ).filter { it.userId != null }

        val sortKeys = SortKeyGenerator.spreadKeys(taskList.size)
        taskList.zip(sortKeys).forEach { (t, key) -> t.sortKey = key }

        taskRepository.saveAll(taskList)
    }
}
