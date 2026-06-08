package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.newTestBoardCryptoService
import dev.itayp.tasker.crypto.newTestUserCryptoService
import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.response.AccountExportResponse
import dev.itayp.tasker.model.response.CategoryExport
import dev.itayp.tasker.model.response.SettingsExport
import dev.itayp.tasker.model.response.TagExport
import dev.itayp.tasker.model.response.TaskExport
import dev.itayp.tasker.model.response.UserExport
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class AccountImportServiceTest {

    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var userSettingsRepository: UserSettingsRepository
    @Mock lateinit var categoryRepository: BacklogTaskCategoryRepository
    @Mock lateinit var tagRepository: BacklogTaskTagRepository
    @Mock lateinit var taskRepository: BacklogTaskRepository
    @Mock lateinit var accountService: AccountService
    @Mock lateinit var eventPublisher: ApplicationEventPublisher
    @Mock lateinit var boardMembershipService: BoardMembershipService

    private val crypto = newTestUserCryptoService()
    private val boardCrypto = newTestBoardCryptoService()
    private val userId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
    private val boardId = UUID.fromString("00000000-0000-0000-0000-0000000000b0")

    private lateinit var service: AccountImportService

    @BeforeEach
    fun setUp() {
        crypto.ensureUserKey(userId)
        boardCrypto.ensureBoardKey(boardId)
        // Real UserSettingsService gives us the validateSettingsInput helper without re-mocking.
        val userSettingsService = UserSettingsService(userSettingsRepository, eventPublisher, crypto)
        service = AccountImportService(
            accountService = accountService,
            userRepository = userRepository,
            userSettingsRepository = userSettingsRepository,
            userSettingsService = userSettingsService,
            categoryRepository = categoryRepository,
            tagRepository = tagRepository,
            taskRepository = taskRepository,
            userCrypto = crypto,
            boardCrypto = boardCrypto,
            boardMembershipService = boardMembershipService,
        )
    }

    @Test
    fun `happy path imports categories, tags, and tasks with translated FKs and encrypted payloads`() {
        val oldCatId = "11111111-1111-1111-1111-111111111111"
        val oldTagId = "22222222-2222-2222-2222-222222222222"
        val payload = exportPayload(
            categories = listOf(CategoryExport(id = oldCatId, label = "Work", swatchId = "sunshine")),
            tags = listOf(TagExport(id = oldTagId, label = "urgent", colorId = "coral", description = "fast")),
            tasks = listOf(taskExport(categoryId = oldCatId, tagIds = listOf(oldTagId), title = "Buy bread")),
        )
        stubFreshAccount()
        // Repos return the entity they were given but with a new id assigned, mimicking JPA save.
        whenever(categoryRepository.save(any<BacklogTaskCategoryEntity>())).thenAnswer { invocation ->
            (invocation.arguments[0] as BacklogTaskCategoryEntity).apply { id = id ?: UUID.randomUUID() }
        }
        whenever(tagRepository.save(any<BacklogTaskTagEntity>())).thenAnswer { invocation ->
            (invocation.arguments[0] as BacklogTaskTagEntity).apply { id = id ?: UUID.randomUUID() }
        }
        whenever(categoryRepository.findByIdAndBoardId(any(), any())).thenAnswer { invocation ->
            BacklogTaskCategoryEntity().apply {
                this.id = invocation.arguments[0] as UUID
                this.boardId = invocation.arguments[1] as UUID
                this.label = "Work"
                this.swatchId = CategoryColor.SUNSHINE
            }
        }
        whenever(taskRepository.save(any<BacklogTaskEntity>())).thenAnswer { invocation ->
            (invocation.arguments[0] as BacklogTaskEntity).apply { id = id ?: UUID.randomUUID() }
        }
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity().apply { id = userId }))
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val summary = service.import(userId, payload)

        assertEquals(1, summary.categories)
        assertEquals(1, summary.tags)
        assertEquals(1, summary.tasks)
        // The auto-seeded defaults are wiped before insertion.
        verify(categoryRepository).deleteAllByBoardId(boardId)

        // Captured task entity has ciphertext title — encryption was applied under the board DEK.
        val captor = argumentCaptor<BacklogTaskEntity>()
        verify(taskRepository).save(captor.capture())
        val savedTitle = captor.firstValue.title
        assertNotNull(savedTitle)
        assertTrue(!String(savedTitle).contains("Buy bread"), "title must be encrypted, not plaintext")
        assertEquals("Buy bread", boardCrypto.decrypt(boardId, savedTitle))
    }

    @Test
    fun `rejects an unsupported formatVersion`() {
        val payload = exportPayload().copy(formatVersion = 99)
        // Version check runs before any repository interaction, so no other stubs needed.
        assertFailsWith<IllegalArgumentException> { service.import(userId, payload) }
        verify(categoryRepository, never()).deleteAllByBoardId(any())
    }

    @Test
    fun `rejects when account is not empty`() {
        whenever(accountService.isEmptyForImport(userId)).thenReturn(false)
        assertFailsWith<IllegalStateException> { service.import(userId, exportPayload()) }
        verify(categoryRepository, never()).deleteAllByBoardId(any())
    }

    @Test
    fun `rejects a task referencing an unknown category id`() {
        val payload = exportPayload(
            categories = listOf(CategoryExport(id = "cat-1", label = "Work", swatchId = "sunshine")),
            tasks = listOf(taskExport(categoryId = "missing-cat", title = "Orphan")),
        )
        stubFreshAccount()
        whenever(categoryRepository.save(any<BacklogTaskCategoryEntity>())).thenAnswer { invocation ->
            (invocation.arguments[0] as BacklogTaskCategoryEntity).apply { id = id ?: UUID.randomUUID() }
        }
        assertFailsWith<IllegalArgumentException> { service.import(userId, payload) }
    }

    @Test
    fun `rejects an unknown swatchId enum value`() {
        val payload = exportPayload(
            categories = listOf(CategoryExport(id = "cat-1", label = "Work", swatchId = "not-a-color")),
        )
        stubFreshAccount()
        assertFailsWith<IllegalArgumentException> { service.import(userId, payload) }
    }

    @Test
    fun `restores email and emailHash but leaves emailVerifiedAt null`() {
        val payload = exportPayload().copy(
            user = UserExport(
                id = "old-uuid",
                telegramUsername = "alice",
                telegramFirstName = "Alice",
                email = "Alice@Example.COM",
                createdAt = null,
            ),
        )
        stubFreshAccount()
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity().apply { id = userId }))
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        service.import(userId, payload)

        val captor = argumentCaptor<UserEntity>()
        verify(userRepository).save(captor.capture())
        val saved = captor.firstValue
        assertEquals("alice@example.com", crypto.decrypt(userId, saved.email))
        assertEquals(EmailHasher.hash("alice@example.com"), saved.emailHash)
        assertNull(saved.emailVerifiedAt, "verification status must not be restored")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun stubFreshAccount() {
        whenever(accountService.isEmptyForImport(userId)).thenReturn(true)
        whenever(boardMembershipService.resolveSoleBoard(userId)).thenReturn(boardId)
    }

    private fun exportPayload(
        categories: List<CategoryExport> = emptyList(),
        tags: List<TagExport> = emptyList(),
        tasks: List<TaskExport> = emptyList(),
        settings: SettingsExport? = null,
    ) = AccountExportResponse(
        formatVersion = 1,
        exportedAt = Instant.parse("2026-05-25T12:00:00Z").toString(),
        user = UserExport(id = "old-uuid", telegramUsername = "alice", telegramFirstName = "Alice", email = null, createdAt = null),
        settings = settings,
        categories = categories,
        tags = tags,
        tasks = tasks,
    )

    private fun taskExport(
        categoryId: String,
        tagIds: List<String> = emptyList(),
        title: String = "Task",
    ) = TaskExport(
        id = UUID.randomUUID().toString(),
        title = title,
        description = null,
        url = null,
        priority = null,
        deadline = null,
        estimatedMinutes = null,
        status = "todo",
        categoryId = categoryId,
        tagIds = tagIds,
        sortKey = "a",
        createdAt = Instant.parse("2026-05-01T12:00:00Z").toString(),
        updatedAt = null,
        relevantFrom = null,
    )
}
