package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.noopBoardCryptoService
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.BacklogTaskChangeService
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
class TaskAutoArchiveServiceTest {

    @Mock private lateinit var userSettingsRepository: UserSettingsRepository
    @Mock private lateinit var backlogTaskRepository: BacklogTaskRepository
    @Mock private lateinit var taskChangeService: BacklogTaskChangeService
    @Mock private lateinit var boardMembershipService: BoardMembershipService

    private val fixedNow = Instant.parse("2026-05-22T02:30:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private val boardCrypto = noopBoardCryptoService()

    private val service by lazy {
        TaskAutoArchiveService(userSettingsRepository, backlogTaskRepository, taskChangeService, boardCrypto, boardMembershipService, clock)
    }

    private val userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val boardId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
    private val taskId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    // ── archiveStaleDoneTasks ─────────────────────────────────────────────────

    @Test
    fun `skips early when no users have auto-archive configured`() {
        whenever(userSettingsRepository.findAllByAutoArchiveDaysIsNotNull()).thenReturn(emptyList())

        service.archiveStaleDoneTasks()

        verify(backlogTaskRepository, never()).findAllByBoardIdAndStatusAndUpdatedAtBeforeOrderBySortKeyAsc(any(), any(), any())
    }

    @Test
    fun `archives tasks updated before the cutoff and records status change`() {
        val settings = settingsEntity(autoArchiveDays = 7)
        val staleTask = taskEntity(title = "Old task", updatedAt = fixedNow.minus(8, ChronoUnit.DAYS))
        val expectedCutoff = fixedNow.minus(7L, ChronoUnit.DAYS)
        whenever(userSettingsRepository.findAllByAutoArchiveDaysIsNotNull()).thenReturn(listOf(settings))
        whenever(boardMembershipService.resolveSoleBoard(userId)).thenReturn(boardId)
        whenever(backlogTaskRepository.findAllByBoardIdAndStatusAndUpdatedAtBeforeOrderBySortKeyAsc(
            boardId, TaskStatus.DONE, expectedCutoff
        )).thenReturn(listOf(staleTask))
        whenever(backlogTaskRepository.save(any<BacklogTaskEntity>())).thenAnswer { it.arguments[0] }

        service.archiveStaleDoneTasks()

        assertEquals(TaskStatus.ARCHIVED, staleTask.status)
        assertEquals(fixedNow, staleTask.updatedAt)
        verify(taskChangeService).recordStatusChange(userId, taskId, "Old task", TaskStatus.DONE, TaskStatus.ARCHIVED)
    }

    @Test
    fun `does not archive tasks when none are stale`() {
        val settings = settingsEntity(autoArchiveDays = 7)
        whenever(userSettingsRepository.findAllByAutoArchiveDaysIsNotNull()).thenReturn(listOf(settings))
        whenever(boardMembershipService.resolveSoleBoard(userId)).thenReturn(boardId)
        whenever(backlogTaskRepository.findAllByBoardIdAndStatusAndUpdatedAtBeforeOrderBySortKeyAsc(
            any(), any(), any()
        )).thenReturn(emptyList())

        service.archiveStaleDoneTasks()

        verify(backlogTaskRepository, never()).save(any())
        verify(taskChangeService, never()).recordStatusChange(any(), any(), any(), any(), any())
    }

    @Test
    fun `uses correct per-user cutoff based on their autoArchiveDays setting`() {
        val settings = settingsEntity(autoArchiveDays = 30)
        val expectedCutoff = fixedNow.minus(30L, ChronoUnit.DAYS)
        whenever(userSettingsRepository.findAllByAutoArchiveDaysIsNotNull()).thenReturn(listOf(settings))
        whenever(boardMembershipService.resolveSoleBoard(userId)).thenReturn(boardId)
        whenever(backlogTaskRepository.findAllByBoardIdAndStatusAndUpdatedAtBeforeOrderBySortKeyAsc(
            boardId, TaskStatus.DONE, expectedCutoff
        )).thenReturn(emptyList())

        service.archiveStaleDoneTasks()

        verify(backlogTaskRepository).findAllByBoardIdAndStatusAndUpdatedAtBeforeOrderBySortKeyAsc(
            boardId, TaskStatus.DONE, expectedCutoff
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun settingsEntity(autoArchiveDays: Int) = UserSettingsEntity().apply {
        this.userId = this@TaskAutoArchiveServiceTest.userId
        this.autoArchiveDays = autoArchiveDays
    }

    private fun taskEntity(title: String, updatedAt: Instant) = BacklogTaskEntity().apply {
        this.id = taskId
        this.boardId = this@TaskAutoArchiveServiceTest.boardId
        this.title = title.toByteArray(Charsets.UTF_8)
        this.status = TaskStatus.DONE
        this.updatedAt = updatedAt
    }
}
