package dev.itayp.tasker

import dev.itayp.tasker.ai.conversation.ConversationEntity
import dev.itayp.tasker.ai.conversation.ConversationRepository
import dev.itayp.tasker.ai.conversation.ConversationStatus
import dev.itayp.tasker.ai.conversation.MessageEntity
import dev.itayp.tasker.ai.conversation.MessageRepository
import dev.itayp.tasker.jpa.BacklogTaskCategoryEntity
import dev.itayp.tasker.jpa.BacklogTaskEntity
import dev.itayp.tasker.jpa.BacklogTaskTagEntity
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.crypto.BoardCryptoService
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.model.response.AccountExportResponse
import dev.itayp.tasker.model.response.BoardExport
import dev.itayp.tasker.model.response.CategoryExport
import dev.itayp.tasker.model.response.TagExport
import dev.itayp.tasker.model.response.TaskExport
import dev.itayp.tasker.model.response.UserExport
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.planning.PlannedTaskRepository
import dev.itayp.tasker.planning.PlannedTaskSlotRepository
import dev.itayp.tasker.planning.PlanningSessionRepository
import dev.itayp.tasker.service.AccountImportService
import dev.itayp.tasker.service.AccountService
import dev.itayp.tasker.service.BacklogTaskService
import dev.itayp.tasker.service.BoardMembershipService
import dev.itayp.tasker.service.DemoDataSeeder
import dev.itayp.tasker.service.UserAuthService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("prod")
@ContextConfiguration(initializers = [AbstractIntegrationTest.Initializer::class])
class PostgresIntegrationTest(
    @Autowired val rest: TestRestTemplate,
    @Autowired val userRepository: UserRepository,
    @Autowired val taskRepository: BacklogTaskRepository,
    @Autowired val tagRepository: BacklogTaskTagRepository,
    @Autowired val categoryRepository: BacklogTaskCategoryRepository,
    @Autowired val accountService: AccountService,
    @Autowired val conversationRepository: ConversationRepository,
    @Autowired val messageRepository: MessageRepository,
    @Autowired val accountImportService: AccountImportService,
    @Autowired val userAuthService: UserAuthService,
    @Autowired val demoDataSeeder: DemoDataSeeder,
    @Autowired val planningSessionRepository: PlanningSessionRepository,
    @Autowired val plannedTaskRepository: PlannedTaskRepository,
    @Autowired val plannedTaskSlotRepository: PlannedTaskSlotRepository,
    @Autowired val userCrypto: UserCryptoService,
    @Autowired val boardCrypto: BoardCryptoService,
    @Autowired val boardMembershipService: BoardMembershipService,
    @Autowired val backlogTaskService: BacklogTaskService,
    @Autowired val jdbc: JdbcTemplate,
    @Autowired val objectMapper: ObjectMapper,
) {

    @Test
    fun `health endpoint returns UP with PostgreSQL backing`() {
        val response = rest.getForEntity("/actuator/health", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"status\":\"UP\"")
    }

    @Test
    fun `liveness probe returns UP`() {
        val response = rest.getForEntity("/actuator/health/liveness", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"status\":\"UP\"")
    }

    @Test
    fun `users can be persisted and retrieved from PostgreSQL`() {
        val user = UserEntity().apply {
            id = UUID.randomUUID()
            telegramId = System.nanoTime()
            telegramFirstName = "Integration".toByteArray()
            telegramUsername = "pg_integration_test"
            createdAt = Instant.now()
            lastLoginAt = Instant.now()
        }
        userRepository.save(user)

        val found = userRepository.findById(user.id!!)
        assertThat(found).isPresent
        assertThat(found.get().telegramId).isEqualTo(user.telegramId)
        assertThat(found.get().telegramFirstName?.toString(Charsets.UTF_8)).isEqualTo("Integration")
    }

    @Test
    fun `deleteUserData removes tasks and tag join rows against PostgreSQL UUID columns`() {
        val userId = UUID.randomUUID()
        userAuthService.ensureDevUser(userId, telegramId = System.nanoTime())
        val boardId = boardMembershipService.resolveDefaultBoard(userId)

        val testCategory = BacklogTaskCategoryEntity().apply { this.boardId = boardId; label = "Work"; swatchId = CategoryColor.SUNSHINE }
        categoryRepository.save(testCategory)

        val tag = BacklogTaskTagEntity().apply { this.boardId = boardId; label = "urgent"; colorId = TagColor.CORAL }
        tagRepository.save(tag)

        val task = BacklogTaskEntity().apply {
            this.boardId = boardId
            title = "Task to delete".toByteArray()
            status = TaskStatus.TODO
            category = testCategory
            sortKey = "a"
            createdAt = Instant.now()
            tags = mutableSetOf(tag)
        }
        taskRepository.save(task)

        accountService.deleteUserData(userId)

        assertThat(taskRepository.findAllByBoardIdOrderBySortKeyAsc(boardId)).isEmpty()
        assertThat(tagRepository.findAllByBoardId(boardId)).isEmpty()
        assertThat(categoryRepository.findAllByBoardId(boardId)).isEmpty()
    }

    @Test
    fun `deleteAccount removes all data including planning sessions, planned tasks, and slots`() {
        val userId = UUID.randomUUID()
        userAuthService.ensureDevUser(userId, telegramId = System.nanoTime())
        val boardId = boardMembershipService.resolveDefaultBoard(userId)

        // DemoDataSeeder creates 8 tasks, 1 planning session, 3 planned tasks each with 1 slot
        demoDataSeeder.seed(userId)

        // Sanity-check: the seed actually created planning data
        val sessions = planningSessionRepository.findAllByUserIdOrderByStartedAtDesc(userId)
        assertThat(sessions).isNotEmpty()
        val sessionId = sessions.first().id!!
        assertThat(plannedTaskRepository.findAllBySessionIdOrderByPosition(sessionId)).isNotEmpty()

        accountService.deleteAccount(userId)

        // User row is gone
        assertThat(userRepository.findById(userId)).isEmpty

        // All board-owned rows are gone (the board itself was the user's sole board)
        assertThat(taskRepository.findAllByBoardIdOrderBySortKeyAsc(boardId)).isEmpty()
        assertThat(categoryRepository.findAllByBoardId(boardId)).isEmpty()

        // All planning rows are gone — use JDBC for tables without a findAllByUserId method
        assertThat(planningSessionRepository.findAllByUserIdOrderByStartedAtDesc(userId)).isEmpty()

        val remainingPlannedTasks = jdbc.queryForObject(
            "SELECT COUNT(*) FROM planned_task WHERE user_id = ?",
            Int::class.java,
            userId,
        )!!
        assertThat(remainingPlannedTasks).isZero()

        // planned_task_slot has no user_id; verify via join that no orphaned slots remain
        val remainingSlots = jdbc.queryForObject(
            """SELECT COUNT(*) FROM planned_task_slot pts
               JOIN planned_task pt ON pt.id = pts.planned_task_id
               WHERE pt.user_id = ?""",
            Int::class.java,
            userId,
        )!!
        assertThat(remainingSlots).isZero()
    }

    @Test
    fun `ai_conversation system_prompt CLOB survives a round-trip through PostgreSQL`() {
        val user = userRepository.save(UserEntity().apply {
            id = UUID.randomUUID()
            telegramId = System.nanoTime()
            telegramFirstName = "AI Test".toByteArray()
            telegramUsername = "ai_clob_test_${System.nanoTime()}"
            createdAt = Instant.now()
            lastLoginAt = Instant.now()
        })

        // Sensitive columns are now BYTEA — round-trip raw bytes through the encrypted column.
        val longSystemPrompt = ByteArray(8_000) { 'x'.code.toByte() }

        val conversation = conversationRepository.save(ConversationEntity().apply {
            this.userId = user.id
            this.conversationType = "weekly-planning"
            this.model = "claude-sonnet-4-6"
            this.ttlDays = 7
            this.status = ConversationStatus.ACTIVE
            this.createdAt = Instant.now()
            this.lastActivityAt = Instant.now()
            this.systemPrompt = longSystemPrompt
        })

        val loaded = conversationRepository.findById(conversation.id!!).orElseThrow()
        assertThat(loaded.systemPrompt).hasSize(8_000)
        assertThat(loaded.systemPrompt).isEqualTo(longSystemPrompt)
    }

    @Test
    fun `ai_message content and tool_calls_json BYTEA columns survive a round-trip through PostgreSQL`() {
        val user = userRepository.save(UserEntity().apply {
            id = UUID.randomUUID()
            telegramId = System.nanoTime()
            telegramFirstName = "AI Msg Test".toByteArray()
            telegramUsername = "ai_msg_clob_test_${System.nanoTime()}"
            createdAt = Instant.now()
            lastLoginAt = Instant.now()
        })

        val conversation = conversationRepository.save(ConversationEntity().apply {
            this.userId = user.id
            this.conversationType = "weekly-planning"
            this.model = "claude-sonnet-4-6"
            this.ttlDays = 7
            this.status = ConversationStatus.ACTIVE
            this.createdAt = Instant.now()
            this.lastActivityAt = Instant.now()
        })

        val longContent = ByteArray(8_000) { 'a'.code.toByte() }
        val longToolCallsJson = ByteArray(8_000) { 'b'.code.toByte() }

        val message = messageRepository.save(MessageEntity().apply {
            this.conversationId = conversation.id
            this.role = "assistant"
            this.content = longContent
            this.toolCallsJson = longToolCallsJson
            this.position = 0
            this.createdAt = Instant.now()
        })

        val loaded = messageRepository.findById(message.id!!).orElseThrow()
        assertThat(loaded.content).hasSize(8_000)
        assertThat(loaded.content).isEqualTo(longContent)
        assertThat(loaded.toolCallsJson).hasSize(8_000)
        assertThat(loaded.toolCallsJson).isEqualTo(longToolCallsJson)
    }

    @Test
    fun `account import populates tasks and stores ciphertext in title column`() {
        // Use the dev-user path with a random UUID: it creates an authenticated user
        // with a DEK, a board, and the auto-seeded default categories but no tasks/tags —
        // exactly what import expects. The demo path additionally seeds tasks, which would
        // trip the isEmptyForImport guard.
        val userId = UUID.randomUUID()
        userAuthService.ensureDevUser(userId, telegramId = System.nanoTime())
        val boardId = boardMembershipService.resolveDefaultBoard(userId)

        val payload = AccountExportResponse(
            formatVersion = 3,
            exportedAt = Instant.parse("2026-05-25T12:00:00Z").toString(),
            user = UserExport(
                telegramUsername = null,
                telegramFirstName = "Alice",
                email = null,
                createdAt = null,
            ),
            settings = null,
            boards = listOf(
                BoardExport(
                    name = "My tasks",
                    role = "OWNER",
                    categories = listOf(
                        CategoryExport(label = "Imported Work", swatchId = "sky"),
                    ),
                    tags = listOf(
                        TagExport(label = "urgent", colorId = "coral", description = null),
                    ),
                    tasks = listOf(
                        TaskExport(
                            title = "ENCRYPT-CHECK-12345",
                            description = "secret notes",
                            url = null,
                            priority = "high",
                            deadline = null,
                            estimatedMinutes = 30,
                            status = "todo",
                            categoryIndex = 0,
                            tagIndexes = listOf(0),
                            sortKey = "a",
                            createdAt = Instant.parse("2026-05-01T12:00:00Z").toString(),
                            updatedAt = null,
                            relevantFrom = null,
                        )
                    ),
                )
            ),
        )

        val summary = accountImportService.import(userId, payload)
        assertThat(summary.tasks).isEqualTo(1)
        assertThat(summary.categories).isEqualTo(1)
        assertThat(summary.tags).isEqualTo(1)

        // Title column holds ciphertext (board DEK): encrypted bytes never contain the marker plaintext.
        val rawTitleBytes: ByteArray = jdbc.queryForList(
            "SELECT title FROM backlog_task WHERE board_id = ?",
            ByteArray::class.java,
            boardId,
        ).single() ?: error("imported task has null title")
        assertThat(String(rawTitleBytes, Charsets.UTF_8))
            .doesNotContain("ENCRYPT-CHECK-12345")
        assertThat(boardCrypto.decrypt(boardId, rawTitleBytes)).isEqualTo("ENCRYPT-CHECK-12345")

        // Imported categories replaced the auto-seeded defaults.
        val categories = categoryRepository.findAllByBoardId(boardId)
        assertThat(categories).singleElement().satisfies({
            assertThat(it.label).isEqualTo("Imported Work")
            assertThat(it.swatchId).isEqualTo(CategoryColor.SKY)
        })
    }

    @Test
    fun `account import of export v3 populates tasks and stores ciphertext in title column`() {
        val json = ClassPathResource("import/export-v3.json").getContentAsString(StandardCharsets.UTF_8)
        val payload = objectMapper.readValue(json, AccountExportResponse::class.java)
        assertThat(payload.formatVersion).isEqualTo(3)
        assertThat(payload.boards[0].tasks).hasSize(8)
        assertThat(payload.boards[0].categories).hasSize(6)

        val userId = UUID.randomUUID()
        userAuthService.ensureDevUser(userId, telegramId = System.nanoTime())
        val boardId = boardMembershipService.resolveDefaultBoard(userId)

        val summary = accountImportService.import(userId, payload)
        assertThat(summary.tasks).isEqualTo(8)
        assertThat(summary.categories).isEqualTo(6)
        assertThat(summary.tags).isEqualTo(2)

        // Each task title is encrypted under the board DEK: the raw column never contains the plaintext.
        val rawTitles: List<ByteArray> = jdbc.queryForList(
            "SELECT title FROM backlog_task WHERE board_id = ?",
            ByteArray::class.java,
            boardId,
        ).filterNotNull()
        assertThat(rawTitles).hasSize(8)
        val decrypted = rawTitles.map { boardCrypto.decrypt(boardId, it) }
        assertThat(decrypted).contains(
            "Prepare weekly team update",
            "Book dentist appointment",
            "Go for a 30-min run",
        )
        rawTitles.forEach { bytes ->
            assertThat(String(bytes, StandardCharsets.UTF_8))
                .doesNotContain("Prepare weekly team update")
        }
    }

    @Test
    fun `getTasksForUser resolves lazy category and tags outside HTTP request context`() {
        val userId = UUID.randomUUID()
        userAuthService.ensureDevUser(userId, telegramId = System.nanoTime())
        val boardId = boardMembershipService.resolveDefaultBoard(userId)

        val category = categoryRepository.save(BacklogTaskCategoryEntity().apply {
            this.boardId = boardId
            label = "Work"
            swatchId = CategoryColor.SUNSHINE
        })
        taskRepository.save(BacklogTaskEntity().apply {
            this.boardId = boardId
            title = boardCrypto.encrypt(boardId, "Lazy-load regression task")
            status = TaskStatus.TODO
            this.category = category
            sortKey = "a"
            createdAt = Instant.now()
        })

        // Simulates being called from a Telegram bot handler (no HTTP request, so open-in-view
        // is inactive). Before @Transactional(readOnly=true) was added to the read path this
        // threw LazyInitializationException on category and tags.
        val tasks = backlogTaskService.getTasksAcrossBoards(userId, null)

        assertThat(tasks).filteredOn { it.title == "Lazy-load regression task" }.singleElement().satisfies({ t ->
            assertThat(t.category.label).isEqualTo("Work")
        })
    }
}
