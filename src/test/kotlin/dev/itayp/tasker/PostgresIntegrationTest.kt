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
import dev.itayp.tasker.model.CategoryColor
import dev.itayp.tasker.model.TagColor
import dev.itayp.tasker.model.TaskStatus
import dev.itayp.tasker.repository.BacklogTaskCategoryRepository
import dev.itayp.tasker.repository.BacklogTaskRepository
import dev.itayp.tasker.repository.BacklogTaskTagRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.AccountService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
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
        val user = UserEntity().apply {
            id = UUID.randomUUID()
            telegramId = System.nanoTime()
            telegramFirstName = "Delete Test".toByteArray()
            telegramUsername = "delete_test_${System.nanoTime()}"
            createdAt = Instant.now()
            lastLoginAt = Instant.now()
        }
        userRepository.save(user)

        val testCategory = BacklogTaskCategoryEntity().apply { userId = user.id; label = "Work"; swatchId = CategoryColor.SUNSHINE }
        categoryRepository.save(testCategory)

        val tag = BacklogTaskTagEntity().apply { userId = user.id; label = "urgent"; colorId = TagColor.CORAL }
        tagRepository.save(tag)

        val task = BacklogTaskEntity().apply {
            userId = user.id
            title = "Task to delete".toByteArray()
            status = TaskStatus.TODO
            category = testCategory
            sortKey = "a"
            createdAt = Instant.now()
            tags = mutableSetOf(tag)
        }
        taskRepository.save(task)

        accountService.deleteUserData(user.id!!)

        assertThat(taskRepository.findAllByUserIdOrderBySortKeyAsc(user.id!!)).isEmpty()
        assertThat(tagRepository.findAllByUserId(user.id!!)).isEmpty()
        assertThat(categoryRepository.findAllByUserId(user.id!!)).isEmpty()
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
}
