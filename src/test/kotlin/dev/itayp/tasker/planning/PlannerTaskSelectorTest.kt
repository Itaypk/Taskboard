package dev.itayp.tasker.planning

import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.repository.BacklogTaskRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class PlannerTaskSelectorTest {

    @Mock private lateinit var backlogTaskRepository: BacklogTaskRepository

    private val today: LocalDate = LocalDate.parse("2026-05-01")
    private val now: Instant = today.atStartOfDay(ZoneOffset.UTC).toInstant()
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val selector by lazy { PlannerTaskSelector(backlogTaskRepository, clock) }

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val sharedCategory = categoryEntity()

    @Test
    fun `empty backlog returns empty selection`() {
        whenever(backlogTaskRepository.findAllByUserIdAndStatus(userId, TaskStatus.TODO))
            .thenReturn(emptyList())

        val result = selector.select(userId)

        assertTrue(result.urgent.isEmpty())
        assertTrue(result.stale.isEmpty())
    }

    @Test
    fun `returns all tasks in urgent when fewer than total slots`() {
        val tasks = listOf(
            task(title = "A", priority = TaskPriority.LOW),
            task(title = "B", priority = TaskPriority.HIGH),
            task(title = "C", priority = TaskPriority.MEDIUM),
        )
        whenever(backlogTaskRepository.findAllByUserIdAndStatus(userId, TaskStatus.TODO))
            .thenReturn(tasks)

        val result = selector.select(userId)

        assertEquals(3, result.urgent.size)
        assertTrue(result.stale.isEmpty())
        // Sorted by descending urgency: HIGH > MEDIUM > LOW
        assertEquals(listOf("B", "C", "A"), result.urgent.map { it.title })
    }

    @Test
    fun `HIGH-priority overdue task ranks above MEDIUM no-deadline`() {
        val overdue = task(title = "overdue", priority = TaskPriority.HIGH, deadline = today.minusDays(2))
        val noDeadline = task(title = "noDeadline", priority = TaskPriority.MEDIUM, deadline = null)
        whenever(backlogTaskRepository.findAllByUserIdAndStatus(userId, TaskStatus.TODO))
            .thenReturn(listOf(noDeadline, overdue))

        val result = selector.select(userId)

        assertEquals("overdue", result.urgent.first().title)
    }

    @Test
    fun `rescheduleCount boosts a task above an otherwise-equal one`() {
        val plain = task(title = "plain", priority = TaskPriority.MEDIUM, rescheduleCount = 0)
        val deferred = task(title = "deferred", priority = TaskPriority.MEDIUM, rescheduleCount = 3)
        whenever(backlogTaskRepository.findAllByUserIdAndStatus(userId, TaskStatus.TODO))
            .thenReturn(listOf(plain, deferred))

        val result = selector.select(userId)

        assertEquals("deferred", result.urgent.first().title)
    }

    @Test
    fun `urgent priority near deadline beats LOW priority with massive reschedule count`() {
        val urgent = task(title = "urgent", priority = TaskPriority.HIGH, deadline = today.plusDays(1))
        val deferredJunk = task(title = "deferredJunk", priority = TaskPriority.LOW, rescheduleCount = 10)
        whenever(backlogTaskRepository.findAllByUserIdAndStatus(userId, TaskStatus.TODO))
            .thenReturn(listOf(deferredJunk, urgent))

        val result = selector.select(userId)

        assertEquals("urgent", result.urgent.first().title)
    }

    @Test
    fun `stale pool surfaces oldest-touched task and excludes recently-modified ones`() {
        // 16 tasks total — fills urgent (12) + stale (3) and leaves one unselected.
        val urgentFillers = (1..12).map {
            task(title = "U$it", priority = TaskPriority.HIGH, deadline = today.plusDays(1))
        }
        val freshlyModified = task(
            title = "fresh",
            priority = TaskPriority.LOW,
            createdAt = now.minus(60, java.time.temporal.ChronoUnit.DAYS),
            updatedAt = now.minus(2, java.time.temporal.ChronoUnit.DAYS),
        )
        val oldestStale = task(
            title = "oldest",
            priority = TaskPriority.LOW,
            createdAt = now.minus(120, java.time.temporal.ChronoUnit.DAYS),
            updatedAt = null,
        )
        val midStale = task(
            title = "mid",
            priority = TaskPriority.LOW,
            createdAt = now.minus(80, java.time.temporal.ChronoUnit.DAYS),
            updatedAt = null,
        )
        val recentStale = task(
            title = "recentish",
            priority = TaskPriority.LOW,
            createdAt = now.minus(30, java.time.temporal.ChronoUnit.DAYS),
            updatedAt = now.minus(20, java.time.temporal.ChronoUnit.DAYS),
        )
        whenever(backlogTaskRepository.findAllByUserIdAndStatus(userId, TaskStatus.TODO))
            .thenReturn(urgentFillers + listOf(freshlyModified, oldestStale, midStale, recentStale))

        val result = selector.select(userId)

        assertEquals(12, result.urgent.size)
        assertEquals(3, result.stale.size)
        // Ordered by oldest first.
        assertEquals(listOf("oldest", "mid", "recentish"), result.stale.map { it.title })
        // Freshly modified task is excluded — modified within the 7-day cutoff.
        assertTrue(result.stale.none { it.title == "fresh" })
        // No overlap.
        val urgentIds = result.urgent.mapTo(mutableSetOf()) { it.id }
        assertTrue(result.stale.none { it.id in urgentIds })
    }

    @Test
    fun `stale pool excludes tasks with rescheduleCount greater than zero`() {
        // 14 fillers + 2 candidates = 16 > totalSlots(15), so the pool split triggers.
        val urgentFillers = (1..14).map {
            task(title = "U$it", priority = TaskPriority.HIGH, deadline = today.plusDays(1))
        }
        val deferredOld = task(
            title = "deferredOld",
            priority = TaskPriority.LOW,
            createdAt = now.minus(120, java.time.temporal.ChronoUnit.DAYS),
            updatedAt = null,
            rescheduleCount = 2,
        )
        val genuinelyStale = task(
            title = "genuinelyStale",
            priority = TaskPriority.LOW,
            createdAt = now.minus(60, java.time.temporal.ChronoUnit.DAYS),
            updatedAt = null,
        )
        whenever(backlogTaskRepository.findAllByUserIdAndStatus(userId, TaskStatus.TODO))
            .thenReturn(urgentFillers + listOf(deferredOld, genuinelyStale))

        val result = selector.select(userId)

        assertEquals(listOf("genuinelyStale"), result.stale.map { it.title })
    }

    private fun categoryEntity() = BacklogTaskCategoryEntity().apply {
        this.id = UUID.randomUUID()
        this.userId = this@PlannerTaskSelectorTest.userId
        this.label = "Work"
        this.swatchId = CategoryColor.SUNSHINE
    }

    private fun task(
        title: String,
        priority: TaskPriority? = null,
        deadline: LocalDate? = null,
        rescheduleCount: Int = 0,
        createdAt: Instant = now.minus(1, java.time.temporal.ChronoUnit.DAYS),
        updatedAt: Instant? = null,
    ): BacklogTaskEntity = BacklogTaskEntity().apply {
        this.id = UUID.randomUUID()
        this.userId = this@PlannerTaskSelectorTest.userId
        this.title = title
        this.status = TaskStatus.TODO
        this.priority = priority
        this.deadline = deadline
        this.category = sharedCategory
        this.tags = mutableSetOf()
        this.sortKey = "a"
        this.createdAt = createdAt
        this.updatedAt = updatedAt
        this.rescheduleCount = rescheduleCount
    }
}
