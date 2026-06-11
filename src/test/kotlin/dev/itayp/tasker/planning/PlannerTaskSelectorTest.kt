package dev.itayp.tasker.planning

import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.service.BoardMembershipService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class PlannerTaskSelectorTest {

    @Mock private lateinit var backlogTaskRepository: BacklogTaskRepository
    @Mock private lateinit var plannedTaskRepository: PlannedTaskRepository
    @Mock private lateinit var plannedTaskSlotRepository: PlannedTaskSlotRepository
    @Mock private lateinit var boardMembershipService: BoardMembershipService

    private val today: LocalDate = LocalDate.parse("2026-05-01")
    private val weekStart: LocalDate = LocalDate.parse("2026-04-27") // Monday of that week
    private val zone: ZoneId = ZoneOffset.UTC
    private val now: Instant = today.atStartOfDay(ZoneOffset.UTC).toInstant()
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val boardCrypto = dev.itayp.tasker.crypto.noopBoardCryptoService()

    private val selector by lazy {
        PlannerTaskSelector(backlogTaskRepository, plannedTaskRepository, plannedTaskSlotRepository, boardCrypto, boardMembershipService, clock)
    }

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val boardId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b0")
    private val sharedCategory = categoryEntity()

    @BeforeEach
    fun stubBoard() {
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(boardId)
    }

    private fun select() = selector.select(userId, today, weekStart, zone)

    @Test
    fun `empty backlog returns empty selection`() {
        whenever(backlogTaskRepository.findAllByBoardIdAndStatus(boardId, TaskStatus.TODO))
            .thenReturn(emptyList())

        val result = select()

        assertTrue(result.urgent.isEmpty())
        assertTrue(result.stale.isEmpty())
        assertTrue(result.alreadyPlanned.isEmpty())
    }

    @Test
    fun `returns all tasks in urgent when fewer than total slots`() {
        val tasks = listOf(
            task(title = "A", priority = TaskPriority.LOW),
            task(title = "B", priority = TaskPriority.HIGH),
            task(title = "C", priority = TaskPriority.MEDIUM),
        )
        whenever(backlogTaskRepository.findAllByBoardIdAndStatus(boardId, TaskStatus.TODO))
            .thenReturn(tasks)
        whenever(plannedTaskRepository.findAllByUserIdAndBacklogTaskIdIn(any(), any())).thenReturn(emptyList())

        val result = select()

        assertEquals(3, result.urgent.size)
        assertTrue(result.stale.isEmpty())
        // Sorted by descending urgency: HIGH > MEDIUM > LOW
        assertEquals(listOf("B", "C", "A"), result.urgent.map { it.title })
    }

    @Test
    fun `HIGH-priority overdue task ranks above MEDIUM no-deadline`() {
        val overdue = task(title = "overdue", priority = TaskPriority.HIGH, deadline = today.minusDays(2))
        val noDeadline = task(title = "noDeadline", priority = TaskPriority.MEDIUM, deadline = null)
        whenever(backlogTaskRepository.findAllByBoardIdAndStatus(boardId, TaskStatus.TODO))
            .thenReturn(listOf(noDeadline, overdue))
        whenever(plannedTaskRepository.findAllByUserIdAndBacklogTaskIdIn(any(), any())).thenReturn(emptyList())

        val result = select()

        assertEquals("overdue", result.urgent.first().title)
    }

    @Test
    fun `rescheduleCount boosts a task above an otherwise-equal one`() {
        val plain = task(title = "plain", priority = TaskPriority.MEDIUM, rescheduleCount = 0)
        val deferred = task(title = "deferred", priority = TaskPriority.MEDIUM, rescheduleCount = 3)
        whenever(backlogTaskRepository.findAllByBoardIdAndStatus(boardId, TaskStatus.TODO))
            .thenReturn(listOf(plain, deferred))
        whenever(plannedTaskRepository.findAllByUserIdAndBacklogTaskIdIn(any(), any())).thenReturn(emptyList())

        val result = select()

        assertEquals("deferred", result.urgent.first().title)
    }

    @Test
    fun `urgent priority near deadline beats LOW priority with massive reschedule count`() {
        val urgent = task(title = "urgent", priority = TaskPriority.HIGH, deadline = today.plusDays(1))
        val deferredJunk = task(title = "deferredJunk", priority = TaskPriority.LOW, rescheduleCount = 10)
        whenever(backlogTaskRepository.findAllByBoardIdAndStatus(boardId, TaskStatus.TODO))
            .thenReturn(listOf(deferredJunk, urgent))
        whenever(plannedTaskRepository.findAllByUserIdAndBacklogTaskIdIn(any(), any())).thenReturn(emptyList())

        val result = select()

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
        whenever(backlogTaskRepository.findAllByBoardIdAndStatus(boardId, TaskStatus.TODO))
            .thenReturn(urgentFillers + listOf(freshlyModified, oldestStale, midStale, recentStale))
        whenever(plannedTaskRepository.findAllByUserIdAndBacklogTaskIdIn(any(), any())).thenReturn(emptyList())

        val result = select()

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
        whenever(backlogTaskRepository.findAllByBoardIdAndStatus(boardId, TaskStatus.TODO))
            .thenReturn(urgentFillers + listOf(deferredOld, genuinelyStale))
        whenever(plannedTaskRepository.findAllByUserIdAndBacklogTaskIdIn(any(), any())).thenReturn(emptyList())

        val result = select()

        assertEquals(listOf("genuinelyStale"), result.stale.map { it.title })
    }

    @Test
    fun `relevantFrom filter uses the caller-provided today, not UTC clock`() {
        // Simulate a user in UTC+12 where local "today" is 2026-05-02 while UTC clock reads 2026-05-01.
        val localToday = LocalDate.parse("2026-05-02")
        val becomesRelevantTomorrowUtc = task(
            title = "relevantTodayLocal",
            priority = TaskPriority.MEDIUM,
            relevantFrom = localToday,
        )
        val futureRelevant = task(
            title = "futureRelevant",
            priority = TaskPriority.MEDIUM,
            relevantFrom = localToday.plusDays(1),
        )
        whenever(backlogTaskRepository.findAllByBoardIdAndStatus(boardId, TaskStatus.TODO))
            .thenReturn(listOf(becomesRelevantTomorrowUtc, futureRelevant))
        whenever(plannedTaskRepository.findAllByUserIdAndBacklogTaskIdIn(any(), any())).thenReturn(emptyList())

        val result = selector.select(userId, localToday, weekStart, zone)

        assertEquals(listOf("relevantTodayLocal"), result.urgent.map { it.title })
        assertTrue(result.stale.isEmpty())
    }

    @Test
    fun `alreadyPlanned maps each task to its earliest slot strictly after the planning window`() {
        val taskA = task(title = "A", priority = TaskPriority.MEDIUM)
        val taskB = task(title = "B", priority = TaskPriority.MEDIUM)
        val taskC = task(title = "C", priority = TaskPriority.MEDIUM)
        whenever(backlogTaskRepository.findAllByBoardIdAndStatus(boardId, TaskStatus.TODO))
            .thenReturn(listOf(taskA, taskB, taskC))

        val plannedA = plannedTask(backlogTaskId = taskA.id!!)
        val plannedB = plannedTask(backlogTaskId = taskB.id!!)
        // taskC has no planned entry — should not appear in alreadyPlanned.
        whenever(plannedTaskRepository.findAllByUserIdAndBacklogTaskIdIn(any(), any()))
            .thenReturn(listOf(plannedA, plannedB))

        val windowEnd = weekStart.plusDays(6) // 2026-05-03 inclusive
        // taskA: one slot in window (ignored) + one slot after window (kept)
        val slotInWindow = slot(plannedA.id!!, startIso = "${windowEnd}T09:00:00Z")
        val slotAfterA1 = slot(plannedA.id!!, startIso = "${windowEnd.plusDays(2)}T09:00:00Z")
        val slotAfterA2 = slot(plannedA.id!!, startIso = "${windowEnd.plusDays(9)}T09:00:00Z")
        // taskB: only a slot after window
        val slotAfterB = slot(plannedB.id!!, startIso = "${windowEnd.plusDays(5)}T15:30:00+02:00")
        whenever(plannedTaskSlotRepository.findAllByPlannedTaskIdIn(any()))
            .thenReturn(listOf(slotInWindow, slotAfterA1, slotAfterA2, slotAfterB))

        val result = select()

        assertEquals(windowEnd.plusDays(2), result.alreadyPlanned[taskA.id!!])
        assertEquals(windowEnd.plusDays(5), result.alreadyPlanned[taskB.id!!])
        assertNull(result.alreadyPlanned[taskC.id!!])
        // Slots after the window are "planned later", never "already scheduled".
        assertTrue(result.alreadyScheduled.isEmpty())
    }

    @Test
    fun `alreadyScheduled maps each task to its latest slot strictly before the planning window`() {
        val taskA = task(title = "A", priority = TaskPriority.MEDIUM)
        val taskB = task(title = "B", priority = TaskPriority.MEDIUM)
        whenever(backlogTaskRepository.findAllByBoardIdAndStatus(boardId, TaskStatus.TODO))
            .thenReturn(listOf(taskA, taskB))

        val plannedA = plannedTask(backlogTaskId = taskA.id!!)
        val plannedB = plannedTask(backlogTaskId = taskB.id!!)
        whenever(plannedTaskRepository.findAllByUserIdAndBacklogTaskIdIn(any(), any()))
            .thenReturn(listOf(plannedA, plannedB))

        // taskA: two slots before the window (the later one is kept) — e.g. the current week's plan.
        val beforeA1 = slot(plannedA.id!!, startIso = "${weekStart.minusDays(6)}T09:00:00Z")
        val beforeA2 = slot(plannedA.id!!, startIso = "${weekStart.minusDays(2)}T09:00:00Z")
        // taskA also has a slot inside the window, which must be ignored entirely.
        val inWindowA = slot(plannedA.id!!, startIso = "${weekStart.plusDays(1)}T09:00:00Z")
        // taskB: a slot after the window → "planned later", not "already scheduled".
        val afterB = slot(plannedB.id!!, startIso = "${weekStart.plusDays(9)}T09:00:00Z")
        whenever(plannedTaskSlotRepository.findAllByPlannedTaskIdIn(any()))
            .thenReturn(listOf(beforeA1, beforeA2, inWindowA, afterB))

        val result = select()

        assertEquals(weekStart.minusDays(2), result.alreadyScheduled[taskA.id!!])
        assertNull(result.alreadyScheduled[taskB.id!!])
        assertNull(result.alreadyPlanned[taskA.id!!])
        assertEquals(weekStart.plusDays(9), result.alreadyPlanned[taskB.id!!])
    }

    private fun categoryEntity() = BacklogTaskCategoryEntity().apply {
        this.id = UUID.randomUUID()
        this.boardId = this@PlannerTaskSelectorTest.boardId
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
        relevantFrom: LocalDate? = null,
    ): BacklogTaskEntity = BacklogTaskEntity().apply {
        this.id = UUID.randomUUID()
        this.boardId = this@PlannerTaskSelectorTest.boardId
        this.title = title.toByteArray(Charsets.UTF_8)
        this.status = TaskStatus.TODO
        this.priority = priority
        this.deadline = deadline
        this.category = sharedCategory
        this.tags = mutableSetOf()
        this.sortKey = "a"
        this.createdAt = createdAt
        this.updatedAt = updatedAt
        this.rescheduleCount = rescheduleCount
        this.relevantFrom = relevantFrom
    }

    private fun plannedTask(backlogTaskId: UUID): PlannedTaskEntity = PlannedTaskEntity().apply {
        this.id = UUID.randomUUID()
        this.sessionId = UUID.randomUUID()
        this.userId = this@PlannerTaskSelectorTest.userId
        this.backlogTaskId = backlogTaskId
        this.title = "ignored".toByteArray(Charsets.UTF_8)
        this.position = 0
    }

    private fun slot(plannedTaskId: UUID, startIso: String): PlannedTaskSlotEntity =
        PlannedTaskSlotEntity().apply {
            this.id = UUID.randomUUID()
            this.plannedTaskId = plannedTaskId
            this.startIso = startIso
            this.endIso = startIso
        }
}
