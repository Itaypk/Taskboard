package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.BoardCryptoService
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Seeds a brand-new account with a small **tutorial** backlog instead of throwaway sample data. The
 * tasks are real rows (`tutorial = true`) whose content teaches the product by having the user use
 * it. They are immutable client-side, excluded from the engagement signal, and cleared only by
 * explicit user action (a "clear tutorial" button or completing them one by one).
 *
 * Unlike [DemoDataSeeder] (kept for dev/test sample data), this seeds no fake planning session — a
 * new user's plan should start empty.
 */
@Service
class TutorialSeeder(
    private val categoryRepository: BacklogTaskCategoryRepository,
    private val taskRepository: BacklogTaskRepository,
    private val boardCrypto: BoardCryptoService,
    private val boardMembershipService: BoardMembershipService,
    private val clock: Clock,
) {

    @Transactional
    fun seed(userId: UUID) {
        val boardId = boardMembershipService.resolveDefaultBoard(userId)
        // Any seeded default category satisfies the not-null category; tutorial tasks aren't about
        // categorisation, so we don't fuss over which one.
        val category = categoryRepository.findAllByBoardId(boardId).firstOrNull() ?: return
        val now = clock.instant()

        val titles = listOf(
            "Welcome to Backlog.fyi — mark this task done to get started",
            "Add your own task (try the + button)",
            "Add an email or Telegram so your tasks are saved",
            "Set your assistant preferences",
            "Clear these tutorial tasks when you're ready",
        )

        val sortKeys = SortKeyGenerator.spreadKeys(titles.size)
        val tasks = titles.zip(sortKeys).map { (title, key) ->
            BacklogTaskEntity().apply {
                this.boardId = boardId
                this.title = boardCrypto.encrypt(boardId, title)
                this.status = TaskStatus.TODO
                this.category = category
                this.sortKey = key
                this.createdAt = now
                this.tutorial = true
            }
        }
        taskRepository.saveAll(tasks)
    }
}
