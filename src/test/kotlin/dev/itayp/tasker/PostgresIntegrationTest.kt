package dev.itayp.tasker

import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.repository.UserRepository
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
}
