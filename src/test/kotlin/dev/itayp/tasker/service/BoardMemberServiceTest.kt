package dev.itayp.tasker.service

import dev.itayp.tasker.jpa.BoardMembershipEntity
import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BoardMembershipRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
class BoardMemberServiceTest {

    @Mock private lateinit var boardMembershipRepository: BoardMembershipRepository
    @Mock private lateinit var boardMembershipService: BoardMembershipService
    @Mock private lateinit var backlogTaskRepository: BacklogTaskRepository
    @Mock private lateinit var displayNameResolver: MemberDisplayNameResolver

    private val service by lazy {
        BoardMemberService(boardMembershipRepository, boardMembershipService, backlogTaskRepository, displayNameResolver)
    }

    private val boardId = UUID.randomUUID()
    private val owner = UUID.randomUUID()
    private val member = UUID.randomUUID()

    private fun membership(userId: UUID, role: BoardRole, joinedAt: Instant) =
        BoardMembershipEntity().apply {
            this.id = UUID.randomUUID()
            this.boardId = this@BoardMemberServiceTest.boardId
            this.userId = userId
            this.role = role
            this.joinedAt = joinedAt
        }

    // ── listMembers ───────────────────────────────────────────────────────────

    @Test
    fun `listMembers returns members oldest-first with resolved names`() {
        whenever(boardMembershipService.requireMember(owner, boardId)).thenReturn(BoardRole.OWNER)
        val older = membership(owner, BoardRole.OWNER, Instant.parse("2026-01-01T00:00:00Z"))
        val newer = membership(member, BoardRole.MEMBER, Instant.parse("2026-02-01T00:00:00Z"))
        whenever(boardMembershipRepository.findAllByBoardId(boardId)).thenReturn(listOf(newer, older))
        whenever(displayNameResolver.resolve(any())).thenReturn(mapOf(owner to "Alice", member to "Bob"))

        val result = service.listMembers(owner, boardId)

        assertEquals(listOf(owner, member), result.map { it.userId })
        assertEquals("Alice", result.first().displayName)
    }

    // ── setRole ───────────────────────────────────────────────────────────────

    @Test
    fun `setRole refuses demoting the last owner`() {
        whenever(boardMembershipService.requireMember(owner, boardId)).thenReturn(BoardRole.OWNER)
        whenever(boardMembershipRepository.findByUserIdAndBoardId(owner, boardId))
            .thenReturn(membership(owner, BoardRole.OWNER, Instant.now()))
        whenever(boardMembershipRepository.countByBoardIdAndRole(boardId, BoardRole.OWNER)).thenReturn(1)

        assertThrows<LastOwnerException> { service.setRole(owner, boardId, owner, BoardRole.MEMBER) }
        verify(boardMembershipRepository, never()).save(any())
    }

    @Test
    fun `setRole promotes a member to owner`() {
        whenever(boardMembershipService.requireMember(owner, boardId)).thenReturn(BoardRole.OWNER)
        val target = membership(member, BoardRole.MEMBER, Instant.now())
        whenever(boardMembershipRepository.findByUserIdAndBoardId(member, boardId)).thenReturn(target)

        service.setRole(owner, boardId, member, BoardRole.OWNER)

        assertEquals(BoardRole.OWNER, target.role)
        verify(boardMembershipRepository).save(target)
    }

    @Test
    fun `setRole rejects a non-owner caller`() {
        whenever(boardMembershipService.requireMember(member, boardId)).thenReturn(BoardRole.MEMBER)

        assertThrows<BoardOwnerRequiredException> { service.setRole(member, boardId, owner, BoardRole.MEMBER) }
    }

    // ── removeMember ──────────────────────────────────────────────────────────

    @Test
    fun `removeMember refuses removing yourself`() {
        whenever(boardMembershipService.requireMember(owner, boardId)).thenReturn(BoardRole.OWNER)

        assertThrows<CannotRemoveSelfException> { service.removeMember(owner, boardId, owner) }
    }

    @Test
    fun `removeMember clears claims and deletes the membership`() {
        whenever(boardMembershipService.requireMember(owner, boardId)).thenReturn(BoardRole.OWNER)
        val target = membership(member, BoardRole.MEMBER, Instant.now())
        whenever(boardMembershipRepository.findByUserIdAndBoardId(member, boardId)).thenReturn(target)

        service.removeMember(owner, boardId, member)

        verify(backlogTaskRepository).clearAssigneeOnBoardForUser(boardId, member)
        verify(boardMembershipRepository).delete(target)
    }

    // ── leaveBoard ────────────────────────────────────────────────────────────

    @Test
    fun `leaveBoard refuses the sole member`() {
        whenever(boardMembershipRepository.findByUserIdAndBoardId(owner, boardId))
            .thenReturn(membership(owner, BoardRole.OWNER, Instant.now()))
        whenever(boardMembershipRepository.findAllByBoardId(boardId))
            .thenReturn(listOf(membership(owner, BoardRole.OWNER, Instant.now())))

        assertThrows<SoleMemberException> { service.leaveBoard(owner, boardId) }
    }

    @Test
    fun `leaveBoard refuses leaving your last board`() {
        whenever(boardMembershipRepository.findByUserIdAndBoardId(owner, boardId))
            .thenReturn(membership(owner, BoardRole.OWNER, Instant.now()))
        whenever(boardMembershipRepository.findAllByBoardId(boardId))
            .thenReturn(listOf(membership(owner, BoardRole.OWNER, Instant.now()), membership(member, BoardRole.MEMBER, Instant.now())))
        whenever(boardMembershipService.listBoardIds(owner)).thenReturn(listOf(boardId))

        assertThrows<LastBoardException> { service.leaveBoard(owner, boardId) }
    }

    @Test
    fun `leaveBoard auto-promotes the longest-tenured member when the last owner leaves`() {
        val ownerMembership = membership(owner, BoardRole.OWNER, Instant.parse("2026-01-01T00:00:00Z"))
        val olderMember = membership(member, BoardRole.MEMBER, Instant.parse("2026-02-01T00:00:00Z"))
        val newerMember = membership(UUID.randomUUID(), BoardRole.MEMBER, Instant.parse("2026-03-01T00:00:00Z"))
        whenever(boardMembershipRepository.findByUserIdAndBoardId(owner, boardId)).thenReturn(ownerMembership)
        whenever(boardMembershipRepository.findAllByBoardId(boardId))
            .thenReturn(listOf(ownerMembership, newerMember, olderMember))
        whenever(boardMembershipService.listBoardIds(owner)).thenReturn(listOf(boardId, UUID.randomUUID()))
        whenever(boardMembershipRepository.countByBoardIdAndRole(boardId, BoardRole.OWNER)).thenReturn(1)

        service.leaveBoard(owner, boardId)

        // The earliest-joined remaining member is promoted, then the owner is removed.
        val promoted = argumentCaptor<BoardMembershipEntity>()
        verify(boardMembershipRepository).save(promoted.capture())
        assertEquals(member, promoted.firstValue.userId)
        assertEquals(BoardRole.OWNER, promoted.firstValue.role)
        verify(boardMembershipRepository).delete(ownerMembership)
    }
}
