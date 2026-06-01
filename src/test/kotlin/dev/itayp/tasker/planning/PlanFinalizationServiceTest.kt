package dev.itayp.tasker.planning

import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.crypto.noopUserCryptoService
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.model.UserSettings
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.Mockito
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

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

    private val crypto = noopUserCryptoService()

    private val service by lazy {
        PlanFinalizationService(
            planningSessionService, backlogTaskService, plannedTaskService,
            userRepository, userSettingsService,
            planInviteDispatcher, emailProps, crypto,
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

    @BeforeEach
    fun stubDefaults() {
        // Opt out of invites by default so tests that don't care about email don't NPE. Lenient because
        // diff-empty paths short-circuit before the email gate is consulted.
        Mockito.lenient().`when`(userSettingsService.getOrCreate(any())).thenReturn(settings(calendarInviteEmail = false))
    }

    private fun optInWithVerifiedEmail() {
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(calendarInviteEmail = true))
        whenever(userSettingsService.getLocale(userId)).thenReturn(java.util.Locale.ENGLISH)
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(verifiedUser("alice@example.com")))
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

    // ── Calendar invite dispatch ─────────────────────────────────────────────

    @Test
    fun `complete dispatches invites when user has verified email and opted in`() {
        val user = verifiedUser("alice@example.com")
        whenever(userSettingsService.getOrCreate(userId)).thenReturn(settings(calendarInviteEmail = true))
        whenever(userSettingsService.getLocale(userId)).thenReturn(java.util.Locale.ENGLISH)
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))

        service.complete(userId, sessionId, planWithTasks)

        // No previous plan, so every task is a fresh invite (summary is irrelevant to dispatch).
        val captor = argumentCaptor<AgreedPlan>()
        verify(planInviteDispatcher).dispatch(
            eq("alice@example.com"),
            eq("noreply@backlog.fyi"),
            eq("Backlog.fyi"),
            captor.capture(),
            any(),
        )
        assertEquals(planWithTasks.tasks, captor.firstValue.tasks)
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
            this.email = "unverified@example.com".toByteArray(Charsets.UTF_8)
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

        val captor = argumentCaptor<AgreedPlan>()
        verify(planInviteDispatcher).dispatch(
            eq("alice@example.com"),
            eq("noreply@backlog.fyi"),
            eq("Backlog.fyi"),
            captor.capture(),
            any(),
        )
        assertEquals(planWithTasks.tasks, captor.firstValue.tasks)
    }

    @Test
    fun `revisePlan does not dispatch when not opted in`() {
        service.revisePlan(userId, sessionId, planWithTasks)

        verify(planInviteDispatcher, never()).dispatch(any(), any(), any(), any(), any())
    }

    // ── revisePlan: diff-based invite dispatch ─────────────────────────────────

    @Test
    fun `revisePlan sends no emails when the plan is unchanged`() {
        whenever(plannedTaskService.findForSession(userId, sessionId)).thenReturn(planWithTasks.tasks)

        service.revisePlan(userId, sessionId, planWithTasks)

        verify(planInviteDispatcher, never()).dispatch(any(), any(), any(), any(), any())
        verify(planInviteDispatcher, never()).dispatchUpdates(any(), any(), any(), any(), any())
        verify(planInviteDispatcher, never()).dispatchCancellations(any(), any(), any(), any(), any())
    }

    @Test
    fun `revisePlan invites only the newly added slot`() {
        whenever(plannedTaskService.findForSession(userId, sessionId))
            .thenReturn(listOf(AgreedPlanTask(taskId = taskId1, title = "Task A", slots = listOf(slot))))
        optInWithVerifiedEmail()

        service.revisePlan(userId, sessionId, planWithTasks)

        val captor = argumentCaptor<AgreedPlan>()
        verify(planInviteDispatcher).dispatch(any(), any(), any(), captor.capture(), any())
        assertEquals(listOf(taskId2), captor.firstValue.tasks.map { it.taskId })
        verify(planInviteDispatcher, never()).dispatchUpdates(any(), any(), any(), any(), any())
        verify(planInviteDispatcher, never()).dispatchCancellations(any(), any(), any(), any(), any())
    }

    @Test
    fun `revisePlan cancels invites for a removed task`() {
        whenever(plannedTaskService.findForSession(userId, sessionId)).thenReturn(planWithTasks.tasks)
        optInWithVerifiedEmail()

        // Drop Task B.
        val trimmed = AgreedPlan(tasks = listOf(planWithTasks.tasks[0]), summary = "Just A.")
        service.revisePlan(userId, sessionId, trimmed)

        val captor = argumentCaptor<List<AgreedPlanTask>>()
        verify(planInviteDispatcher).dispatchCancellations(any(), any(), any(), captor.capture(), any())
        assertEquals(listOf(taskId2), captor.firstValue.map { it.taskId })
        verify(planInviteDispatcher, never()).dispatch(any(), any(), any(), any(), any())
        verify(planInviteDispatcher, never()).dispatchUpdates(any(), any(), any(), any(), any())
    }

    @Test
    fun `revisePlan sends an update when a slot's title changes at the same time`() {
        whenever(plannedTaskService.findForSession(userId, sessionId))
            .thenReturn(listOf(AgreedPlanTask(taskId = taskId1, title = "Task A", slots = listOf(slot))))
        optInWithVerifiedEmail()

        val renamed = AgreedPlan(
            tasks = listOf(AgreedPlanTask(taskId = taskId1, title = "Task A (renamed)", slots = listOf(slot))),
            summary = "Renamed A.",
        )
        service.revisePlan(userId, sessionId, renamed)

        val captor = argumentCaptor<AgreedPlan>()
        verify(planInviteDispatcher).dispatchUpdates(any(), any(), any(), captor.capture(), any())
        assertEquals(listOf(taskId1), captor.firstValue.tasks.map { it.taskId })
        verify(planInviteDispatcher, never()).dispatch(any(), any(), any(), any(), any())
        verify(planInviteDispatcher, never()).dispatchCancellations(any(), any(), any(), any(), any())
    }

    @Test
    fun `revisePlan treats a time move as a cancellation plus a fresh invite`() {
        whenever(plannedTaskService.findForSession(userId, sessionId))
            .thenReturn(listOf(AgreedPlanTask(taskId = taskId1, title = "Task A", slots = listOf(slot))))
        optInWithVerifiedEmail()

        val movedSlot = AgreedTimeSlot("2026-05-13T14:00:00+02:00", "2026-05-13T16:00:00+02:00")
        val moved = AgreedPlan(
            tasks = listOf(AgreedPlanTask(taskId = taskId1, title = "Task A", slots = listOf(movedSlot))),
            summary = "Moved A.",
        )
        service.revisePlan(userId, sessionId, moved)

        verify(planInviteDispatcher).dispatch(any(), any(), any(), any(), any())
        verify(planInviteDispatcher).dispatchCancellations(any(), any(), any(), any(), any())
        verify(planInviteDispatcher, never()).dispatchUpdates(any(), any(), any(), any(), any())
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
        this.email = email.toByteArray(Charsets.UTF_8)
        this.emailVerifiedAt = Instant.now()
    }

    private fun settings(calendarInviteEmail: Boolean) = UserSettings(
        userId = userId,
        displayName = null,
        contextBlock = null,
        timeZone = "UTC",
        preferredLanguage = "en-US",
        calendarInviteEmail = calendarInviteEmail,
        gender = null,
        agentDescription = null,
        planningCron = null,
        weekStartDay = null,
        autoArchiveDays = null,
    )
}
