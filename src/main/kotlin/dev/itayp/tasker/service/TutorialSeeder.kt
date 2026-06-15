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

    /**
     * A seeded tutorial card. [url] carries an in-app deep link the client resolves: an internal route
     * (`/settings/<tab>`) or an action token (`app:clear-tutorial`). These bypass the API's `https?://`
     * URL validation because they're written straight to the repository — users can never create them.
     */
    private data class TutorialCard(val title: String, val url: String?, val description: String?)

    @Transactional
    fun seed(userId: UUID) {
        val boardId = boardMembershipService.resolveDefaultBoard(userId)
        val categories = categoryRepository.findAllByBoardId(boardId)
        if (categories.isEmpty()) return
        val now = clock.instant()

        val cards = listOf(
            TutorialCard(
                "Welcome to Backlog.fyi — mark this task done to get started",
                null,
                "Tap a task to open it, then mark it done to check it off.",
            ),
            TutorialCard(
                "Add your own task",
                null,
                "Use the + button up top to pin your first note to the board.",
            ),
            TutorialCard(
                "Save your tasks — add an email or Telegram",
                "/settings/general",
                "Open settings to connect a login so your tasks stick around.",
            ),
            TutorialCard(
                "Set your assistant preferences",
                "/settings/assistant",
                "Open settings to tell the assistant a bit about you.",
            ),
            TutorialCard(
                "Clear these tutorial tasks when you're ready",
                "app:clear-tutorial",
                "Done exploring? This clears the whole tutorial in one tap.",
            ),
        )

        val sortKeys = SortKeyGenerator.spreadKeys(cards.size)
        val tasks = cards.zip(sortKeys).mapIndexed { index, (card, key) ->
            BacklogTaskEntity().apply {
                this.boardId = boardId
                this.title = boardCrypto.encrypt(boardId, card.title)
                this.description = card.description?.let { boardCrypto.encrypt(boardId, it) }
                this.url = card.url
                this.status = TaskStatus.TODO
                // Spread the cards across distinct categories for a "rainbow" of post-it colours; tutorial
                // tasks aren't really about categorisation, so the exact mapping doesn't matter.
                this.category = categories[index % categories.size]
                this.sortKey = key
                this.createdAt = now
                this.tutorial = true
            }
        }
        taskRepository.saveAll(tasks)
    }
}
