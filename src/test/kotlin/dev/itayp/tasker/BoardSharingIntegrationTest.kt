package dev.itayp.tasker

import dev.itayp.tasker.model.BoardRole
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.request.CreateBacklogTaskRequest
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BoardInvitationRepository
import dev.itayp.tasker.repository.BoardMembershipRepository
import dev.itayp.tasker.repository.BoardRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.AccountService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardInvitationService
import dev.itayp.tasker.service.BoardMembershipService
import dev.itayp.tasker.util.CapabilityTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import java.util.UUID

/**
 * End-to-end board-sharing coverage against real Postgres (validates Liquibase 003, bean wiring, and
 * — critically — the raw-SQL §6 account-deletion paths that unit tests can't exercise).
 */
@SpringBootTest
@ActiveProfiles("prod")
@ContextConfiguration(initializers = [AbstractIntegrationTest.Initializer::class])
class BoardSharingIntegrationTest(
    @Autowired val userAuthService: dev.itayp.tasker.service.UserAuthService,
    @Autowired val invitationService: BoardInvitationService,
    @Autowired val invitationRepository: BoardInvitationRepository,
    @Autowired val membershipService: BoardMembershipService,
    @Autowired val membershipRepository: BoardMembershipRepository,
    @Autowired val backlogTaskService: BacklogTaskService,
    @Autowired val categoryRepository: BacklogTaskCategoryRepository,
    @Autowired val taskRepository: BacklogTaskRepository,
    @Autowired val boardRepository: BoardRepository,
    @Autowired val userRepository: UserRepository,
    @Autowired val accountService: AccountService,
    @Autowired val jdbcTemplate: JdbcTemplate,
) {

    private fun register(email: String): UUID =
        (userAuthService.loginByEmail(email) as dev.itayp.tasker.service.EmailLoginOutcome.Success).user.id!!

    private fun boardOf(userId: UUID): UUID = membershipService.listBoardIds(userId).first()

    /**
     * Sends an invitation and returns a usable plaintext token for it. Only the digest is stored
     * (the plaintext lives solely in the email), so the test swaps in the digest of a token it knows.
     */
    private fun inviteAndGetToken(ownerId: UUID, boardId: UUID, email: String): String {
        invitationService.invite(ownerId, boardId, email)
        val known = CapabilityTokens.generate()
        val row = invitationRepository.findAll().first { it.boardId == boardId }
        row.tokenHash = CapabilityTokens.hash(known)
        invitationRepository.save(row)
        return known
    }

    private fun seedTaskWithClaim(ownerId: UUID, boardId: UUID): UUID {
        val categoryId = categoryRepository.findAllByBoardId(boardId).first().id!!
        val task = backlogTaskService.createTask(
            ownerId, boardId,
            CreateBacklogTaskRequest(title = "Shared task", categoryId = categoryId.toString()),
        )
        // No assignee endpoint until PR 3; stamp the claim directly to exercise removal side effects.
        val entity = taskRepository.findById(task.id).orElseThrow()
        entity.assigneeUserId = ownerId
        taskRepository.save(entity)
        return task.id
    }

    @Test
    fun `invite then accept lets a second member see the board's tasks`() {
        val owner = register("owner-${UUID.randomUUID()}@example.com")
        val invitee = register("invitee-${UUID.randomUUID()}@example.com")
        val boardId = boardOf(owner)
        val taskId = seedTaskWithClaim(owner, boardId)

        val token = inviteAndGetToken(owner, boardId, "anything@example.com")

        val accepted = invitationService.accept(token, invitee)

        assertThat(accepted.boardId).isEqualTo(boardId)
        assertThat(membershipService.listBoardIds(invitee)).contains(boardId)
        assertThat(membershipService.requireMember(invitee, boardId)).isEqualTo(BoardRole.MEMBER)
        // The invitee now reads the owner's task through board membership.
        assertThat(backlogTaskService.getTasks(invitee, boardId, null).map { it.id }).contains(taskId)
    }

    @Test
    fun `deleting the sole owner of a shared board auto-promotes and preserves history`() {
        val owner = register("owner-${UUID.randomUUID()}@example.com")
        val member = register("member-${UUID.randomUUID()}@example.com")
        val boardId = boardOf(owner)
        val taskId = seedTaskWithClaim(owner, boardId)

        invitationService.accept(inviteAndGetToken(owner, boardId, "x@example.com"), member)

        accountService.deleteAccount(owner)

        // The board and its content survive; the remaining member is auto-promoted to OWNER.
        assertThat(boardRepository.existsById(boardId)).isTrue()
        assertThat(userRepository.existsById(owner)).isFalse()
        assertThat(membershipRepository.findByUserIdAndBoardId(member, boardId)?.role).isEqualTo(BoardRole.OWNER)
        assertThat(membershipRepository.findByUserIdAndBoardId(owner, boardId)).isNull()
        // The departed owner's claim is cleared (no ghost assignee) but the task itself remains.
        val task = taskRepository.findById(taskId).orElseThrow()
        assertThat(task.assigneeUserId).isNull()
        // The CREATED change event survives the actor, with the actor nulled.
        val events = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM backlog_task_change_event WHERE board_id = ?", Int::class.java, boardId,
        )
        assertThat(events).isGreaterThanOrEqualTo(1)
        val orphanedActor = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM backlog_task_change_event WHERE board_id = ? AND actor_user_id = ?",
            Int::class.java, boardId, owner,
        )
        assertThat(orphanedActor).isEqualTo(0)
    }

    @Test
    fun `deleting a sole member deletes their board and content`() {
        val solo = register("solo-${UUID.randomUUID()}@example.com")
        val boardId = boardOf(solo)
        val taskId = seedTaskWithClaim(solo, boardId)

        accountService.deleteAccount(solo)

        assertThat(boardRepository.existsById(boardId)).isFalse()
        assertThat(taskRepository.existsById(taskId)).isFalse()
        assertThat(userRepository.existsById(solo)).isFalse()
        val watermarks = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM backlog_task_watermark WHERE board_id = ?", Int::class.java, boardId,
        )
        assertThat(watermarks).isEqualTo(0)
    }
}
