package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.noopBoardCryptoService
import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.jpa.BoardEntity
import dev.itayp.tasker.jpa.BoardMembershipEntity
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.planning.BacklogTaskChangeService
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import dev.itayp.tasker.repository.BoardMembershipRepository
import dev.itayp.tasker.repository.BoardRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

@ExtendWith(MockitoExtension::class)
class BoardServiceTest {

    @Mock private lateinit var boardRepository: BoardRepository
    @Mock private lateinit var boardMembershipRepository: BoardMembershipRepository
    @Mock private lateinit var categoryRepository: BacklogTaskCategoryRepository
    @Mock private lateinit var tagRepository: BacklogTaskTagRepository
    @Mock private lateinit var backlogTaskRepository: BacklogTaskRepository
    @Mock private lateinit var boardMembershipService: BoardMembershipService
    @Mock private lateinit var taskChangeService: BacklogTaskChangeService
    @Mock private lateinit var jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate

    private val boardCrypto = noopBoardCryptoService()
    private val clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)

    private val service: BoardService by lazy {
        BoardService(
            boardRepository, boardMembershipRepository, categoryRepository, tagRepository, backlogTaskRepository,
            boardCrypto, boardMembershipService, taskChangeService, jdbcTemplate, clock,
        )
    }

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val boardId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b0")

    @Test
    fun `listBoardsForUser orders by membership age and decrypts names`() {
        val olderBoard = boardEntity(name = "Default")
        val newerBoard = boardEntity(name = "Side project")
        // Repository order is unspecified; the service must sort by joinedAt.
        whenever(boardMembershipRepository.findAllByUserId(userId)).thenReturn(listOf(
            membership(newerBoard, BoardRole.MEMBER, joinedAt = Instant.parse("2026-03-01T00:00:00Z")),
            membership(olderBoard, BoardRole.OWNER, joinedAt = Instant.parse("2026-01-01T00:00:00Z")),
        ))
        whenever(boardRepository.findAllById(any())).thenReturn(listOf(newerBoard, olderBoard))

        val result = service.listBoardsForUser(userId)

        assertEquals(listOf("Default", "Side project"), result.map { it.name })
        assertEquals(listOf(BoardRole.OWNER, BoardRole.MEMBER), result.map { it.role })
        assertEquals(olderBoard.id, result.first().id)
    }

    @Test
    fun `listBoardsForUser returns empty list for user with no memberships`() {
        whenever(boardMembershipRepository.findAllByUserId(userId)).thenReturn(emptyList())
        whenever(boardRepository.findAllById(any())).thenReturn(emptyList())

        assertEquals(emptyList(), service.listBoardsForUser(userId))
    }

    @Test
    fun `createBoard trims the name, seeds defaults, and returns an OWNER summary`() {
        whenever(boardRepository.save(any<BoardEntity>())).thenAnswer { it.arguments[0] as BoardEntity }
        whenever(boardRepository.findById(any())).thenAnswer { inv ->
            Optional.of(boardEntity(name = "ignored").apply { id = inv.arguments[0] as UUID })
        }

        val summary = service.createBoard(userId, "  Side project  ")

        assertEquals("Side project", summary.name)
        assertEquals(BoardRole.OWNER, summary.role)
        // Default category set is seeded into the new board.
        verify(categoryRepository, org.mockito.kotlin.times(UserService.DEFAULT_CATEGORIES.size)).save(any())
    }

    @Test
    fun `createBoard rejects a blank name`() {
        assertFailsWith<IllegalArgumentException> { service.createBoard(userId, "   ") }
    }

    @Test
    fun `duplicateBoard copies categories, tags, and tasks into a new board owned by the caller`() {
        val sourceBoardId = UUID.fromString("00000000-0000-0000-0000-0000000000c0")
        whenever(boardMembershipService.requireMember(userId, sourceBoardId)).thenReturn(BoardRole.MEMBER)
        val sourceBoard = boardEntity(name = "Original").apply { id = sourceBoardId; mascot = "mr_roboto" }
        whenever(boardRepository.save(any<BoardEntity>())).thenAnswer { it.arguments[0] as BoardEntity }
        whenever(boardRepository.findById(any())).thenAnswer { inv ->
            val id = inv.arguments[0] as UUID
            if (id == sourceBoardId) Optional.of(sourceBoard)
            else Optional.of(boardEntity(name = "ignored").apply { this.id = id; mascot = "mr_roboto" })
        }

        val sourceCategory = BacklogTaskCategoryEntity().apply {
            id = UUID.randomUUID(); boardId = sourceBoardId; label = "Home"; swatchId = CategoryColor.SAGE
        }
        val sourceTag = BacklogTaskTagEntity().apply {
            id = UUID.randomUUID(); boardId = sourceBoardId; label = "urgent"; colorId = TagColor.CORAL
        }
        whenever(categoryRepository.findAllByBoardId(sourceBoardId)).thenReturn(listOf(sourceCategory))
        whenever(categoryRepository.save(any<BacklogTaskCategoryEntity>())).thenAnswer { it.arguments[0] as BacklogTaskCategoryEntity }
        whenever(tagRepository.findAllByBoardId(sourceBoardId)).thenReturn(listOf(sourceTag))
        whenever(tagRepository.save(any<BacklogTaskTagEntity>())).thenAnswer { it.arguments[0] as BacklogTaskTagEntity }

        val sourceTask = BacklogTaskEntity().apply {
            id = UUID.randomUUID()
            boardId = sourceBoardId
            title = "Buy boxes".toByteArray(Charsets.UTF_8)
            status = TaskStatus.DONE
            category = sourceCategory
            tags = mutableSetOf(sourceTag)
            sortKey = "a0"
            assigneeUserId = UUID.randomUUID()
        }
        whenever(backlogTaskRepository.findAllByBoardIdOrderBySortKeyAsc(sourceBoardId)).thenReturn(listOf(sourceTask))
        whenever(backlogTaskRepository.save(any<BacklogTaskEntity>())).thenAnswer { inv ->
            (inv.arguments[0] as BacklogTaskEntity).also { if (it.id == null) it.id = UUID.randomUUID() }
        }

        val summary = service.duplicateBoard(userId, sourceBoardId, "  Original (copy)  ", resetTaskStatus = true)

        assertEquals("Original (copy)", summary.name)
        assertEquals(BoardRole.OWNER, summary.role)
        assertEquals(1, summary.memberCount)
        assertNotEquals(sourceBoardId, summary.id)

        val savedTask = org.mockito.kotlin.argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(savedTask.capture())
        assertEquals(summary.id, savedTask.firstValue.boardId)
        assertEquals("Buy boxes", savedTask.firstValue.title?.toString(Charsets.UTF_8))
        assertEquals(TaskStatus.TODO, savedTask.firstValue.status) // reset from DONE
        assertNull(savedTask.firstValue.assigneeUserId)
        assertEquals("Home", savedTask.firstValue.category?.label)
        assertEquals(setOf("urgent"), savedTask.firstValue.tags.map { it.label }.toSet())
    }

    @Test
    fun `duplicateBoard keeps the original task status when resetTaskStatus is false`() {
        val sourceBoardId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
        whenever(boardMembershipService.requireMember(userId, sourceBoardId)).thenReturn(BoardRole.OWNER)
        val sourceBoard = boardEntity(name = "Original").apply { id = sourceBoardId }
        whenever(boardRepository.save(any<BoardEntity>())).thenAnswer { it.arguments[0] as BoardEntity }
        whenever(boardRepository.findById(any())).thenAnswer { inv ->
            val id = inv.arguments[0] as UUID
            if (id == sourceBoardId) Optional.of(sourceBoard)
            else Optional.of(boardEntity(name = "ignored").apply { this.id = id })
        }
        whenever(categoryRepository.findAllByBoardId(sourceBoardId)).thenReturn(emptyList())
        whenever(tagRepository.findAllByBoardId(sourceBoardId)).thenReturn(emptyList())
        val sourceTask = BacklogTaskEntity().apply {
            id = UUID.randomUUID(); boardId = sourceBoardId; title = "Pack".toByteArray(Charsets.UTF_8); status = TaskStatus.DONE
        }
        whenever(backlogTaskRepository.findAllByBoardIdOrderBySortKeyAsc(sourceBoardId)).thenReturn(listOf(sourceTask))
        whenever(backlogTaskRepository.save(any<BacklogTaskEntity>())).thenAnswer { inv ->
            (inv.arguments[0] as BacklogTaskEntity).also { if (it.id == null) it.id = UUID.randomUUID() }
        }

        service.duplicateBoard(userId, sourceBoardId, "Copy", resetTaskStatus = false)

        val savedTask = org.mockito.kotlin.argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(savedTask.capture())
        assertEquals(TaskStatus.DONE, savedTask.firstValue.status)
    }

    @Test
    fun `duplicateBoard excludes seeded tutorial tasks`() {
        val sourceBoardId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
        whenever(boardMembershipService.requireMember(userId, sourceBoardId)).thenReturn(BoardRole.OWNER)
        val sourceBoard = boardEntity(name = "Original").apply { id = sourceBoardId }
        whenever(boardRepository.save(any<BoardEntity>())).thenAnswer { it.arguments[0] as BoardEntity }
        whenever(boardRepository.findById(any())).thenAnswer { inv ->
            val id = inv.arguments[0] as UUID
            if (id == sourceBoardId) Optional.of(sourceBoard)
            else Optional.of(boardEntity(name = "ignored").apply { this.id = id })
        }
        whenever(categoryRepository.findAllByBoardId(sourceBoardId)).thenReturn(emptyList())
        whenever(tagRepository.findAllByBoardId(sourceBoardId)).thenReturn(emptyList())
        val tutorialTask = BacklogTaskEntity().apply {
            id = UUID.randomUUID(); boardId = sourceBoardId; title = "Try this out".toByteArray(Charsets.UTF_8)
            status = TaskStatus.TODO; sortKey = "a0"; tutorial = true
        }
        val realTask = BacklogTaskEntity().apply {
            id = UUID.randomUUID(); boardId = sourceBoardId; title = "Buy boxes".toByteArray(Charsets.UTF_8)
            status = TaskStatus.TODO; sortKey = "a1"; tutorial = false
        }
        whenever(backlogTaskRepository.findAllByBoardIdOrderBySortKeyAsc(sourceBoardId))
            .thenReturn(listOf(tutorialTask, realTask))
        whenever(backlogTaskRepository.save(any<BacklogTaskEntity>())).thenAnswer { inv ->
            (inv.arguments[0] as BacklogTaskEntity).also { if (it.id == null) it.id = UUID.randomUUID() }
        }

        service.duplicateBoard(userId, sourceBoardId, "Copy", resetTaskStatus = false)

        val savedTask = org.mockito.kotlin.argumentCaptor<BacklogTaskEntity>()
        verify(backlogTaskRepository).save(savedTask.capture())
        assertEquals("Buy boxes", savedTask.firstValue.title?.toString(Charsets.UTF_8))
    }

    @Test
    fun `duplicateBoard requires membership on the source board`() {
        val sourceBoardId = UUID.randomUUID()
        whenever(boardMembershipService.requireMember(userId, sourceBoardId)).thenThrow(BoardAccessDeniedException(userId, sourceBoardId))

        assertFailsWith<BoardAccessDeniedException> {
            service.duplicateBoard(userId, sourceBoardId, "Copy", resetTaskStatus = true)
        }
        verify(boardRepository, never()).save(any())
    }

    @Test
    fun `updateBoard re-encrypts the name and sets the mascot for an owner`() {
        whenever(boardMembershipService.requireMember(userId, boardId)).thenReturn(BoardRole.OWNER)
        val board = boardEntity(name = "Old").apply { id = boardId }
        whenever(boardRepository.findById(boardId)).thenReturn(Optional.of(board))
        whenever(boardRepository.save(any<BoardEntity>())).thenAnswer { it.arguments[0] as BoardEntity }

        val summary = service.updateBoard(userId, boardId, "New name", "mr_roboto")

        assertEquals("New name", summary.name)
        assertEquals("New name", board.name?.toString(Charsets.UTF_8))
        assertEquals("mr_roboto", summary.mascot)
        assertEquals("mr_roboto", board.mascot)
    }

    @Test
    fun `updateBoard leaves the mascot unchanged when null and normalizes unknown values`() {
        whenever(boardMembershipService.requireMember(userId, boardId)).thenReturn(BoardRole.OWNER)
        val board = boardEntity(name = "Old").apply { id = boardId; mascot = "stationery" }
        whenever(boardRepository.findById(boardId)).thenReturn(Optional.of(board))
        whenever(boardRepository.save(any<BoardEntity>())).thenAnswer { it.arguments[0] as BoardEntity }

        // null mascot leaves it as-is …
        assertEquals("stationery", service.updateBoard(userId, boardId, "New name", null).mascot)
        // … and an unknown id falls back to the default.
        assertEquals("pineapple", service.updateBoard(userId, boardId, "New name", "nope").mascot)
    }

    @Test
    fun `updateBoard refuses a non-owner`() {
        whenever(boardMembershipService.requireMember(userId, boardId)).thenReturn(BoardRole.MEMBER)

        assertFailsWith<BoardOwnerRequiredException> { service.updateBoard(userId, boardId, "New", null) }
        verify(boardRepository, never()).save(any())
    }

    @Test
    fun `deleteBoard removes content and the board row when others remain`() {
        whenever(boardMembershipService.requireMember(userId, boardId)).thenReturn(BoardRole.OWNER)
        whenever(boardMembershipService.listBoardIds(userId)).thenReturn(listOf(boardId, UUID.randomUUID()))

        service.deleteBoard(userId, boardId)

        // Tasks/tags/categories/membership/key/board — six board-scoped deletes plus the task-tags join.
        verify(jdbcTemplate).update(eq("DELETE FROM board WHERE id = ?"), eq(boardId))
        verify(jdbcTemplate).update(eq("DELETE FROM backlog_task WHERE board_id = ?"), eq(boardId))
        verify(jdbcTemplate).update(eq("DELETE FROM board_data_key WHERE board_id = ?"), eq(boardId))
    }

    @Test
    fun `deleteBoard refuses to delete the user's last board`() {
        whenever(boardMembershipService.requireMember(userId, boardId)).thenReturn(BoardRole.OWNER)
        whenever(boardMembershipService.listBoardIds(userId)).thenReturn(listOf(boardId))

        assertFailsWith<LastBoardException> { service.deleteBoard(userId, boardId) }
        verify(jdbcTemplate, never()).update(eq("DELETE FROM board WHERE id = ?"), any<Any>())
    }

    @Test
    fun `deleteBoard refuses a non-owner`() {
        whenever(boardMembershipService.requireMember(userId, boardId)).thenReturn(BoardRole.MEMBER)

        assertFailsWith<BoardOwnerRequiredException> { service.deleteBoard(userId, boardId) }
        verify(jdbcTemplate, never()).update(any<String>(), any<Any>())
    }

    private fun boardEntity(name: String) = BoardEntity().apply {
        this.id = UUID.randomUUID()
        this.name = name.toByteArray(Charsets.UTF_8)
        this.createdAt = Instant.parse("2026-01-01T00:00:00Z")
    }

    private fun membership(board: BoardEntity, role: BoardRole, joinedAt: Instant) =
        BoardMembershipEntity().apply {
            this.id = UUID.randomUUID()
            this.boardId = board.id
            this.userId = this@BoardServiceTest.userId
            this.role = role
            this.joinedAt = joinedAt
        }
}
