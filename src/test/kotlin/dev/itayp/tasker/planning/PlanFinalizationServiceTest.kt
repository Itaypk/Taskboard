package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.planning.dto.AgreedPlan
import dev.itayp.tasker.planning.dto.AgreedPlanTask
import dev.itayp.tasker.planning.dto.AgreedTimeSlot
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.UserSettingsService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class PlanFinalizationServiceTest {

    @Mock lateinit var planningSessionService: PlanningSessionService
    @Mock lateinit var backlogTaskService: BacklogTaskService
    @Mock lateinit var plannedTaskService: PlannedTaskService
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var userSettingsService: UserSettingsService
    @Mock lateinit var planInviteDispatcher: PlanInviteDispatcher

    private val emailProps = EmailProperties(
        enabled = true,
        from = "noreply@backlog.fyi",
        fromName = "Backlog.fyi",
    )

    private val service by lazy {
        PlanFinalizationService(
            planningSessionService, backlogTaskService, plannedTaskService,
            userRepository, userSettingsService,
            planInviteDispatcher, emailProps,
        )
    }

    private val userId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val taskId1 = UUID.randomUUID()
    private val taskId2 = UUID.randomUUID()

    private val slot = AgreedTimeSlot("2026-05-11T09:00:00+02:00", "2026-05-11T11:00:00+02:00")

    private val planWithTasks = AgreedPlan(
        tasks = listOf(
            AgreedPlanTask(taskId = taskId1, title = "Task A", slots = listOf(slot)),
            AgreedPlanTask(taskId = taskId2, title = "Task B", slots = listOf(slot)),
        ),
        summary = "Agreed on A and B.",
    )

    private val planAdHocOnly = AgreedPlan(
        tasks = listOf(
            AgreedPlanTask(taskId = null, title = "Ad-hoc task", slots = listOf(slot)),
        ),
        summary = "Ad-hoc only.",
    )

    @BeforeEach
    fun stubDefaults() {
        // Opt out of invites by default so tests that don't care about email don't NPE.
        whenever(userSettingsService.getOrCreate(any())).thenReturn(settings(calendarInviteEmail = false))
    }

    // ── Session completion ───────────────────────────────────────────────────

    @Test
    fun `complete delegates session finalization with correct summary`() {
        service.complete(userId, sessionId, planWithTasks)

        verify(planningSessionService).completeSession(userId, sessionId, "Agreed on A and B.")
    }

    // ── Task stamping ────────────────────────────────────────────────────────

    @Test
    fun `complete stamps session id onto all tasks with a backlog uuid`() {
        service.complete(userId, sessionId, planWithTasks)

        verify(backlogTaskService).stampPlanningSession(
            eq(userId),
            eq(listOf(taskId1, taskId2)),
            eq(sessionId),
        )
    }

    @Test
    fun `complete skips stampPlanningSession when plan contains only ad-hoc tasks`() {
        service.complete(userId, sessionId, planAdHocOnly)

        verify(backlogTaskService, never()).stampPlanningSession(any(), any(), any())
    }

    // ── Calendar invite dispatch ─────────────────────────────────────────────

    @Test
    fun `complete dispatches invites when user has verified email and opted in`() {
        val user = verifiedUser("alice@example.com")
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(calendarInviteEmail = true))
        whenever(userSettingsService.getLocale(userId)).thenReturn(java.util.Locale.ENGLISH)
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))

        service.complete(userId, sessionId, planWithTasks)

        verify(planInviteDispatcher).dispatch(
            eq("alice@example.com"),
            eq("noreply@backlog.fyi"),
            eq("Backlog.fyi"),
            eq(planWithTasks),
            any(),
        )
    }

    @Test
    fun `complete does not dispatch when calendarInviteEmail is false`() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(calendarInviteEmail = false))

        service.complete(userId, sessionId, planWithTasks)

        verify(planInviteDispatcher, never()).dispatch(any(), any(), any(), any(), any())
    }

    @Test
    fun `complete does not dispatch when email is not verified`() {
        val user = UserEntity().apply {
            this.id = userId
            this.email = "unverified@example.com"
            this.emailVerifiedAt = null
        }
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(calendarInviteEmail = true))
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))

        service.complete(userId, sessionId, planWithTasks)

        verify(planInviteDispatcher, never()).dispatch(any(), any(), any(), any(), any())
    }

    @Test
    fun `complete does not dispatch when user is not found`() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(calendarInviteEmail = true))
        whenever(userRepository.findById(userId)).thenReturn(Optional.empty())

        service.complete(userId, sessionId, planWithTasks)

        verify(planInviteDispatcher, never()).dispatch(any(), any(), any(), any(), any())
    }

    // ── revisePlan ───────────────────────────────────────────────────────────

    @Test
    fun `revisePlan updates session summary without changing status`() {
        service.revisePlan(userId, sessionId, planWithTasks)

        verify(planningSessionService).updateSummary(sessionId, "Agreed on A and B.")
        verify(planningSessionService, never()).completeSession(any(), any(), any())
    }

    @Test
    fun `revisePlan persists tasks diff-aware`() {
        service.revisePlan(userId, sessionId, planWithTasks)

        verify(plannedTaskService).persist(sessionId, userId, planWithTasks.tasks)
    }

    @Test
    fun `revisePlan stamps session id onto tasks with backlog ids`() {
        service.revisePlan(userId, sessionId, planWithTasks)

        verify(backlogTaskService).stampPlanningSession(
            eq(userId),
            eq(listOf(taskId1, taskId2)),
            eq(sessionId),
        )
    }

    @Test
    fun `revisePlan dispatches invites when opted in`() {
        val user = verifiedUser("alice@example.com")
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(calendarInviteEmail = true))
        whenever(userSettingsService.getLocale(userId)).thenReturn(java.util.Locale.ENGLISH)
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))

        service.revisePlan(userId, sessionId, planWithTasks)

        verify(planInviteDispatcher).dispatch(
            eq("alice@example.com"),
            eq("noreply@backlog.fyi"),
            eq("Backlog.fyi"),
            eq(planWithTasks),
            any(),
        )
    }

    @Test
    fun `revisePlan does not dispatch when not opted in`() {
        service.revisePlan(userId, sessionId, planWithTasks)

        verify(planInviteDispatcher, never()).dispatch(any(), any(), any(), any(), any())
    }

    // ── addTaskToSession ─────────────────────────────────────────────────────

    @Test
    fun `addTaskToSession upserts single task`() {
        val task = AgreedPlanTask(taskId = taskId1, title = "Task A", slots = listOf(slot))
        service.addTaskToSession(userId, sessionId, task)

        verify(plannedTaskService).upsertSingleTask(sessionId, userId, task)
    }

    @Test
    fun `addTaskToSession stamps session when task has a backlog id`() {
        val task = AgreedPlanTask(taskId = taskId1, title = "Task A", slots = listOf(slot))
        service.addTaskToSession(userId, sessionId, task)

        verify(backlogTaskService).stampPlanningSession(eq(userId), eq(listOf(taskId1)), eq(sessionId))
    }

    @Test
    fun `addTaskToSession does not stamp when task is ad-hoc`() {
        val task = AgreedPlanTask(taskId = null, title = "Ad-hoc", slots = listOf(slot))
        service.addTaskToSession(userId, sessionId, task)

        verify(backlogTaskService, never()).stampPlanningSession(any(), any(), any())
    }

    @Test
    fun `addTaskToSession dispatches invite when opted in`() {
        val user = verifiedUser("alice@example.com")
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(calendarInviteEmail = true))
        whenever(userSettingsService.getLocale(userId)).thenReturn(java.util.Locale.ENGLISH)
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))

        val task = AgreedPlanTask(taskId = taskId1, title = "Task A", slots = listOf(slot))
        service.addTaskToSession(userId, sessionId, task)

        verify(planInviteDispatcher).dispatch(any(), any(), any(), any(), any())
    }

    @Test
    fun `addTaskToSession does not dispatch when not opted in`() {
        val task = AgreedPlanTask(taskId = taskId1, title = "Task A", slots = listOf(slot))
        service.addTaskToSession(userId, sessionId, task)

        verify(planInviteDispatcher, never()).dispatch(any(), any(), any(), any(), any())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun verifiedUser(email: String) = UserEntity().apply {
        this.id = userId
        this.email = email
        this.emailVerifiedAt = Instant.now()
    }

    private fun settings(calendarInviteEmail: Boolean) = UserSettingsEntity().apply {
        this.userId = this@PlanFinalizationServiceTest.userId
        this.calendarInviteEmail = calendarInviteEmail
    }
}
