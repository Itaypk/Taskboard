package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.BoardCryptoService
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.jpa.UserSettingsEntity
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.model.BoardSummary
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskPriority
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.repository.UserSettingsRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@ExtendWith(MockitoExtension::class)
class AccountServiceTest {

    @Mock private lateinit var userRepository: UserRepository
    @Mock private lateinit var taskRepository: BacklogTaskRepository
    @Mock private lateinit var tagRepository: BacklogTaskTagRepository
    @Mock private lateinit var categoryRepository: BacklogTaskCategoryRepository
    @Mock private lateinit var settingsRepository: UserSettingsRepository
    @Mock private lateinit var jdbcTemplate: JdbcTemplate
    @Mock private lateinit var userCrypto: UserCryptoService
    @Mock private lateinit var boardCrypto: BoardCryptoService
    @Mock private lateinit var boardMembershipService: BoardMembershipService
    @Mock private lateinit var boardService: BoardService

    @InjectMocks private lateinit var service: AccountService

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val boardId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b0")
    private val categoryId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val tagId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d1")

    @Test
    fun `exportAccount emits formatVersion 2 and all v2 fields`() {
        val workCategory = BacklogTaskCategoryEntity().apply {
            id = categoryId
            this.boardId = this@AccountServiceTest.boardId
            label = "Work"
            swatchId = CategoryColor.SUNSHINE
        }
        val urgentTag = BacklogTaskTagEntity().apply {
            id = tagId
            this.boardId = this@AccountServiceTest.boardId
            label = "urgent"
            colorId = TagColor.CORAL
            description = "needs attention"
        }

        // Encrypted fields are stored as ByteArray; keep references so we can stub decrypt
        val titleBytes = "Buy bread".toByteArray()
        val descriptionBytes = "From the place on 5th".toByteArray()
        val task = BacklogTaskEntity().apply {
            id = UUID.randomUUID()
            this.boardId = this@AccountServiceTest.boardId
            title = titleBytes
            description = descriptionBytes
            url = "https://example.com"
            priority = TaskPriority.HIGH
            deadline = LocalDate.parse("2026-06-01")
            estimatedMinutes = 15
            status = TaskStatus.TODO
            category = workCategory
            tags = mutableSetOf(urgentTag)
            sortKey = "a3f"
            createdAt = Instant.parse("2026-05-01T12:00:00Z")
            updatedAt = Instant.parse("2026-05-10T12:00:00Z")
            relevantFrom = LocalDate.parse("2026-05-20")
        }

        val telegramFirstNameBytes = "Alice".toByteArray()
        val emailBytes = "alice@example.com".toByteArray()
        val user = UserEntity().apply {
            id = this@AccountServiceTest.userId
            telegramUsername = "alice"
            telegramFirstName = telegramFirstNameBytes
            email = emailBytes
            createdAt = Instant.parse("2026-01-01T00:00:00Z")
        }

        val displayNameBytes = "Alice".toByteArray()
        val contextBlockBytes = "I prefer deep work in the morning".toByteArray()
        val agentDescriptionBytes = "Founder working on X".toByteArray()
        val settings = UserSettingsEntity().apply {
            this.userId = this@AccountServiceTest.userId
            displayName = displayNameBytes
            contextBlock = contextBlockBytes
            timeZone = "Europe/London"
            preferredLanguage = "en-US"
            calendarInviteEmail = true
            gender = "feminine"
            agentDescription = agentDescriptionBytes
            planningCron = "0 30 9 * * MON"
            weekStartDay = "MONDAY"
            autoArchiveDays = 30
        }

        // Personal fields decrypt under the user DEK; task content under the board DEK.
        whenever(userCrypto.decrypt(userId, telegramFirstNameBytes)).thenReturn("Alice")
        whenever(userCrypto.decrypt(userId, emailBytes)).thenReturn("alice@example.com")
        whenever(userCrypto.decrypt(userId, displayNameBytes)).thenReturn("Alice")
        whenever(userCrypto.decrypt(userId, contextBlockBytes)).thenReturn("I prefer deep work in the morning")
        whenever(userCrypto.decrypt(userId, agentDescriptionBytes)).thenReturn("Founder working on X")
        whenever(boardCrypto.decrypt(boardId, titleBytes)).thenReturn("Buy bread")
        whenever(boardCrypto.decrypt(boardId, descriptionBytes)).thenReturn("From the place on 5th")

        whenever(boardService.listBoardsForUser(userId)).thenReturn(
            listOf(BoardSummary(boardId, "My tasks", BoardRole.OWNER, Instant.parse("2026-01-01T00:00:00Z")))
        )
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.of(settings))
        whenever(categoryRepository.findAllByBoardId(boardId)).thenReturn(listOf(workCategory))
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(listOf(urgentTag))
        whenever(taskRepository.findAllByBoardIdOrderBySortKeyAsc(boardId)).thenReturn(listOf(task))

        val result = service.exportAccount(userId)

        assertEquals(2, result.formatVersion)

        assertEquals("alice@example.com", result.user.email)
        assertEquals("Alice", result.user.telegramFirstName)

        val exportedSettings = assertNotNull(result.settings)
        assertEquals(true, exportedSettings.calendarInviteEmail)
        assertEquals("feminine", exportedSettings.gender)
        assertEquals("Founder working on X", exportedSettings.agentDescription)
        assertEquals("0 30 9 * * MON", exportedSettings.planningCron)
        assertEquals("MONDAY", exportedSettings.weekStartDay)
        assertEquals(30, exportedSettings.autoArchiveDays)

        val board = result.boards[0]
        assertEquals("My tasks", board.name)
        assertEquals("OWNER", board.role)
        assertEquals(1, board.tasks.size)
        assertEquals("2026-05-20", board.tasks[0].relevantFrom)
        assertEquals("Work", board.categories[0].label)
        assertEquals("sunshine", board.categories[0].swatchId)
        assertEquals("coral", board.tags[0].colorId)
    }

    @Test
    fun `exportAccount handles null settings and null relevantFrom`() {
        val titleBytes = "No date task".toByteArray()
        val task = BacklogTaskEntity().apply {
            id = UUID.randomUUID()
            this.boardId = this@AccountServiceTest.boardId
            title = titleBytes
            status = TaskStatus.TODO
            sortKey = "b00"
            createdAt = Instant.parse("2026-05-01T12:00:00Z")
            relevantFrom = null
        }
        val user = UserEntity().apply {
            id = this@AccountServiceTest.userId
            email = null
        }

        // null ciphertext → null plaintext (mirrors the crypto services' own null-guard)
        whenever(userCrypto.decrypt(eq(userId), isNull())).thenReturn(null)
        whenever(boardCrypto.decrypt(eq(boardId), eq(titleBytes))).thenReturn("No date task")

        whenever(boardService.listBoardsForUser(userId)).thenReturn(
            listOf(BoardSummary(boardId, "My tasks", BoardRole.OWNER, Instant.parse("2026-01-01T00:00:00Z")))
        )
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        whenever(settingsRepository.findById(userId)).thenReturn(Optional.empty())
        whenever(categoryRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        whenever(tagRepository.findAllByBoardId(boardId)).thenReturn(emptyList())
        whenever(taskRepository.findAllByBoardIdOrderBySortKeyAsc(boardId)).thenReturn(listOf(task))

        val result = service.exportAccount(userId)

        assertEquals(2, result.formatVersion)
        assertNull(result.settings)
        assertNull(result.user.email)
        assertNull(result.boards[0].tasks[0].relevantFrom)
    }
}
