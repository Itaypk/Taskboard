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
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
class BoardServiceTest {

    @Mock private lateinit var boardRepository: BoardRepository
    @Mock private lateinit var boardMembershipRepository: BoardMembershipRepository
    @Mock private lateinit var categoryRepository: BacklogTaskCategoryRepository

    private val boardCrypto = noopBoardCryptoService()
    private val clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)

    private val service: BoardService by lazy {
        BoardService(boardRepository, boardMembershipRepository, categoryRepository, boardCrypto, clock)
    }

    private val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

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
