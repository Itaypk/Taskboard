package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.newTestBoardCryptoService
import dev.itayp.tasker.crypto.newTestUserCryptoService
import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.model.BoardSummary
import dev.itayp.tasker.model.response.AccountExportResponse
import dev.itayp.tasker.model.response.BoardExport
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
import org.mockito.kotlin.eq
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
    @Mock lateinit var boardService: BoardService

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
            boardService = boardService,
        )
    }

    @Test
    fun `happy path imports categories, tags, and tasks with translated FKs and encrypted payloads`() {
        val payload = exportPayload(
            categories = listOf(CategoryExport(label = "Work", swatchId = "sunshine")),
            tags = listOf(TagExport(label = "urgent", colorId = "coral", description = "fast")),
            tasks = listOf(taskExport(categoryIndex = 0, tagIndexes = listOf(0), title = "Buy bread")),
        )
        stubFreshAccount()
        // Repos return the entity they were given but with a new id assigned, mimicking JPA save.
        whenever(categoryRepository.save(any<BacklogTaskCategoryEntity>())).thenAnswer { invocation ->
            (invocation.arguments[0] as BacklogTaskCategoryEntity).apply { id = id ?: UUID.randomUUID() }
        }
        whenever(tagRepository.save(any<BacklogTaskTagEntity>())).thenAnswer { invocation ->
            (invocation.arguments[0] as BacklogTaskTagEntity).apply { id = id ?: UUID.randomUUID() }
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
        val payload = exportPayload().copy(formatVersion = 1)
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
    fun `rejects a task referencing an out-of-range category index`() {
        val payload = exportPayload(
            categories = listOf(CategoryExport(label = "Work", swatchId = "sunshine")),
            tasks = listOf(taskExport(categoryIndex = 5, title = "Orphan")),
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
            categories = listOf(CategoryExport(label = "Work", swatchId = "not-a-color")),
        )
        stubFreshAccount()
        assertFailsWith<IllegalArgumentException> { service.import(userId, payload) }
    }

    @Test
    fun `adopts the exported email on a fresh account, leaving emailVerifiedAt null`() {
        val payload = exportPayload().copy(
            user = UserExport(
                telegramUsername = "alice",
                telegramFirstName = "Alice",
                email = "Alice@Example.COM",
                createdAt = null,
            ),
        )
        stubFreshAccount()
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity().apply { id = userId }))
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val summary = service.import(userId, payload)

        val captor = argumentCaptor<UserEntity>()
        verify(userRepository).save(captor.capture())
        val saved = captor.firstValue
        assertEquals("alice@example.com", crypto.decrypt(userId, saved.email))
        assertEquals(EmailHasher.hash("alice@example.com"), saved.emailHash)
        assertNull(saved.emailVerifiedAt, "verification status must not be restored")
        assertTrue(summary.emailImported)
        assertNull(summary.emailSkipReason)
    }

    @Test
    fun `keeps own email and reports a skip when the account already has a different email`() {
        val payload = exportPayload().copy(
            user = UserExport(telegramUsername = null, telegramFirstName = null, email = "new@example.com", createdAt = null),
        )
        stubFreshAccount()
        val existingHash = EmailHasher.hash("existing@example.com")
        whenever(userRepository.findById(userId))
            .thenReturn(Optional.of(UserEntity().apply { id = userId; emailHash = existingHash }))
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val summary = service.import(userId, payload)

        val captor = argumentCaptor<UserEntity>()
        verify(userRepository).save(captor.capture())
        // The account's own email hash is untouched — the exported email was not adopted.
        assertEquals(existingHash, captor.firstValue.emailHash)
        assertEquals(false, summary.emailImported)
        assertEquals("ACCOUNT_HAS_EMAIL", summary.emailSkipReason)
    }

    @Test
    fun `keeps own email and reports a skip when the exported email belongs to another account`() {
        val payload = exportPayload().copy(
            user = UserExport(telegramUsername = null, telegramFirstName = null, email = "taken@example.com", createdAt = null),
        )
        stubFreshAccount()
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity().apply { id = userId }))
        // Another live account already holds this address.
        val otherUserId = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
        whenever(userRepository.findByEmailHash(EmailHasher.hash("taken@example.com")))
            .thenReturn(UserEntity().apply { id = otherUserId })
        whenever(userRepository.save(any<UserEntity>())).thenAnswer { it.arguments[0] as UserEntity }

        val summary = service.import(userId, payload)

        val captor = argumentCaptor<UserEntity>()
        verify(userRepository).save(captor.capture())
        assertNull(captor.firstValue.emailHash, "must not claim another account's email hash")
        assertEquals(false, summary.emailImported)
        assertEquals("TAKEN", summary.emailSkipReason)
    }

    @Test
    fun `imports multiple boards, reusing the default board and creating the rest`() {
        val secondBoardId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
        val payload = AccountExportResponse(
            formatVersion = 3,
            exportedAt = Instant.parse("2026-05-25T12:00:00Z").toString(),
            user = UserExport(telegramUsername = null, telegramFirstName = null, email = null, createdAt = null),
            settings = null,
            boards = listOf(
                BoardExport(
                    name = "My tasks", role = "OWNER",
                    categories = listOf(CategoryExport(label = "Work", swatchId = "sunshine")),
                    tags = emptyList(),
                    tasks = listOf(taskExport(categoryIndex = 0, title = "A")),
                ),
                BoardExport(
                    name = "Side", role = "OWNER",
                    categories = listOf(CategoryExport(label = "Home", swatchId = "sky")),
                    tags = emptyList(),
                    tasks = listOf(taskExport(categoryIndex = 0, title = "B")),
                ),
            ),
        )
        stubFreshAccount()
        // The real BoardService.createBoard would seed the new board's DEK; mimic that for the mock.
        boardCrypto.ensureBoardKey(secondBoardId)
        whenever(boardService.createBoard(eq(userId), any())).thenReturn(
            BoardSummary(secondBoardId, "Side", BoardRole.OWNER, Instant.parse("2026-01-01T00:00:00Z"))
        )
        whenever(categoryRepository.save(any<BacklogTaskCategoryEntity>())).thenAnswer { inv ->
            (inv.arguments[0] as BacklogTaskCategoryEntity).apply { id = id ?: UUID.randomUUID() }
        }
        whenever(taskRepository.save(any<BacklogTaskEntity>())).thenAnswer { it.arguments[0] }
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(UserEntity().apply { id = userId }))

        val summary = service.import(userId, payload)

        assertEquals(2, summary.categories)
        assertEquals(2, summary.tasks)
        verify(boardService).createBoard(eq(userId), eq("Side"))
        // First board reuses the default board; the second uses the freshly created one.
        verify(categoryRepository).deleteAllByBoardId(boardId)
        verify(categoryRepository).deleteAllByBoardId(secondBoardId)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun stubFreshAccount() {
        whenever(accountService.isEmptyForImport(userId)).thenReturn(true)
        whenever(boardMembershipService.resolveDefaultBoard(userId)).thenReturn(boardId)
    }

    private fun exportPayload(
        categories: List<CategoryExport> = emptyList(),
        tags: List<TagExport> = emptyList(),
        tasks: List<TaskExport> = emptyList(),
        settings: SettingsExport? = null,
    ) = AccountExportResponse(
        formatVersion = 3,
        exportedAt = Instant.parse("2026-05-25T12:00:00Z").toString(),
        user = UserExport(telegramUsername = "alice", telegramFirstName = "Alice", email = null, createdAt = null),
        settings = settings,
        boards = listOf(BoardExport(name = "My tasks", role = "OWNER", categories = categories, tags = tags, tasks = tasks)),
    )

    private fun taskExport(
        categoryIndex: Int,
        tagIndexes: List<Int> = emptyList(),
        title: String = "Task",
    ) = TaskExport(
        title = title,
        description = null,
        url = null,
        priority = null,
        deadline = null,
        estimatedMinutes = null,
        status = "todo",
        categoryIndex = categoryIndex,
        tagIndexes = tagIndexes,
        sortKey = "a",
        createdAt = Instant.parse("2026-05-01T12:00:00Z").toString(),
        updatedAt = null,
        relevantFrom = null,
    )
}
