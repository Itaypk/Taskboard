package dev.itayp.tasker.service

import dev.itayp.tasker.crypto.noopBoardCryptoService
import dev.itayp.tasker.jpa.BoardEntity
import dev.itayp.tasker.jpa.BoardMembershipEntity
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
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

@ExtendWith(MockitoExtension::class)
class BoardServiceTest {

    @Mock private lateinit var boardRepository: BoardRepository
    @Mock private lateinit var boardMembershipRepository: BoardMembershipRepository
    @Mock private lateinit var categoryRepository: BacklogTaskCategoryRepository
    @Mock private lateinit var boardMembershipService: BoardMembershipService
    @Mock private lateinit var jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate

    private val boardCrypto = noopBoardCryptoService()
    private val clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)

    private val service: BoardService by lazy {
        BoardService(
            boardRepository, boardMembershipRepository, categoryRepository,
            boardCrypto, boardMembershipService, jdbcTemplate, clock,
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
    fun `renameBoard re-encrypts the name for an owner`() {
        whenever(boardMembershipService.requireMember(userId, boardId)).thenReturn(BoardRole.OWNER)
        val board = boardEntity(name = "Old").apply { id = boardId }
        whenever(boardRepository.findById(boardId)).thenReturn(Optional.of(board))
        whenever(boardRepository.save(any<BoardEntity>())).thenAnswer { it.arguments[0] as BoardEntity }

        val summary = service.renameBoard(userId, boardId, "New name")

        assertEquals("New name", summary.name)
        assertEquals("New name", board.name?.toString(Charsets.UTF_8))
    }

    @Test
    fun `renameBoard refuses a non-owner`() {
        whenever(boardMembershipService.requireMember(userId, boardId)).thenReturn(BoardRole.MEMBER)

        assertFailsWith<BoardOwnerRequiredException> { service.renameBoard(userId, boardId, "New") }
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
