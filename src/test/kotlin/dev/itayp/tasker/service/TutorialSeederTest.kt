package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.noopBoardCryptoService
import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class TutorialSeederTest {

    @Mock private lateinit var categoryRepository: BacklogTaskCategoryRepository
    @Mock private lateinit var taskRepository: BacklogTaskRepository
    @Mock private lateinit var boardMembershipService: BoardMembershipService

    private val boardCrypto = noopBoardCryptoService()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-14T10:00:00Z"), ZoneOffset.UTC)

    private val seeder: TutorialSeeder by lazy {
        TutorialSeeder(categoryRepository, taskRepository, boardCrypto, boardMembershipService, clock)
    }

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val boardId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b0")

    private fun category(color: CategoryColor): BacklogTaskCategoryEntity =
        BacklogTaskCategoryEntity().apply {
            id = UUID.randomUUID()
            this.boardId = this@TutorialSeederTest.boardId
            label = color.name
            swatchId = color
        }

    private fun seedAndCapture(categories: List<BacklogTaskCategoryEntity>): List<BacklogTaskEntity> {
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(boardId)
        whenever(categoryRepository.findAllByBoardId(boardId)).thenReturn(categories)

        seeder.seed(userId)

        val captor = argumentCaptor<List<BacklogTaskEntity>>()
        verify(taskRepository).saveAll(captor.capture())
        return captor.firstValue
    }

    private fun BacklogTaskEntity.title(): String =
        boardCrypto.decrypt(this@TutorialSeederTest.boardId, title)!!

    @Test
    fun `seeds tutorial backlog with deep-link urls and distinct categories`() {
        val categories = CategoryColor.entries.take(6).map(::category)

        val tasks = seedAndCapture(categories)

        assertEquals(5, tasks.size)
        assertTrue(tasks.all { it.tutorial })
        assertTrue(tasks.all { it.status == TaskStatus.TODO })
        assertTrue(tasks.all { it.createdAt == clock.instant() })

        val byTitleUrl = tasks.associate { it.title() to it.url }
        assertEquals("/settings/general", byTitleUrl["Save your tasks — add an email or Telegram"])
        assertEquals("/settings/assistant", byTitleUrl["Set your assistant preferences"])
        assertEquals("app:clear-tutorial", byTitleUrl["Clear these tutorial tasks when you're ready"])

        // The two intro cards carry no link.
        assertEquals(2, tasks.count { it.url == null })

        // Every card has an encrypted, decryptable description.
        assertTrue(tasks.all { it.description != null && boardCrypto.decrypt(boardId, it.description) != null })

        // Rainbow: with >=5 categories available, no two cards share one.
        val usedCategories = tasks.mapNotNull { it.category?.id }
        assertEquals(usedCategories.size, usedCategories.toSet().size)
    }

    @Test
    fun `does nothing when the board has no categories`() {
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(boardId)
        whenever(categoryRepository.findAllByBoardId(boardId)).thenReturn(emptyList())

        seeder.seed(userId)

        verify(taskRepository, org.mockito.kotlin.never()).saveAll(org.mockito.kotlin.any<List<BacklogTaskEntity>>())
    }
}
