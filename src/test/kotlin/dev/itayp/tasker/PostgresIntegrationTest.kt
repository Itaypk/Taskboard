package dev.itayp.tasker

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
            telegramFirstName = "Integration"
            telegramUsername = "pg_integration_test"
            createdAt = Instant.now()
            lastLoginAt = Instant.now()
        }
        userRepository.save(user)

        val found = userRepository.findById(user.id!!)
        assertThat(found).isPresent
        assertThat(found.get().telegramId).isEqualTo(user.telegramId)
        assertThat(found.get().telegramFirstName).isEqualTo("Integration")
    }

    @Test
    fun `deleteUserData removes tasks and tag join rows against PostgreSQL UUID columns`() {
        val user = UserEntity().apply {
            id = UUID.randomUUID()
            telegramId = System.nanoTime()
            telegramFirstName = "Delete Test"
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
            title = "Task to delete"
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
}
