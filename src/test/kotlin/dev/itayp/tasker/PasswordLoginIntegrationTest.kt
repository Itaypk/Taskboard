package dev.itayp.tasker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Operator-listed local users on a closed instance with the demo off — the self-hosting setup
 * this login exists for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
class PasswordLoginIntegrationTest(@Autowired val rest: TestRestTemplate) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun localUsers(registry: DynamicPropertyRegistry) {
            registry.add("tasker.auth.local.users") { "Alice:${BCryptPasswordEncoder(4).encode("correct horse")}" }
            registry.add("tasker.auth.registration") { "closed" }
        }
    }

    private fun login(username: String, password: String) = rest.postForEntity(
        "/api/auth/password",
        HttpEntity(
            """{"username":"$username","password":"$password"}""",
            HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON },
        ),
        String::class.java,
    )

    @Test
    fun `a listed user signs in, case-insensitively, and the session authenticates`() {
        val response = login("alice", "correct horse")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"claimed\":true")

        val sessionCookie = response.headers[HttpHeaders.SET_COOKIE].orEmpty()
            .first { it.startsWith("SESSION=") }.substringBefore(";")
        val me = rest.exchange(
            "/api/auth/me", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, sessionCookie) }), String::class.java,
        )
        assertThat(me.statusCode).isEqualTo(HttpStatus.OK)

        // A second login lands on the same account rather than provisioning another.
        val again = login("ALICE", "correct horse")
        assertThat(Regex("\"id\":\"([^\"]+)\"").find(again.body!!)!!.groupValues[1])
            .isEqualTo(Regex("\"id\":\"([^\"]+)\"").find(response.body!!)!!.groupValues[1])
    }

    @Test
    fun `wrong password and unknown user get the same answer`() {
        val wrongPassword = login("alice", "wrong")
        val unknownUser = login("mallory", "correct horse")

        assertThat(wrongPassword.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(unknownUser.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(wrongPassword.body).contains("INVALID_CREDENTIALS")
        assertThat(unknownUser.body).contains("INVALID_CREDENTIALS")
        assertThat(wrongPassword.headers[HttpHeaders.SET_COOKIE].orEmpty()).noneMatch { it.startsWith("SESSION=") }
    }

    @Test
    fun `public config advertises password login and hides the demo`() {
        val response = rest.getForEntity("/api/public/config", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"password\":true", "\"demo\":false", "\"registrationOpen\":false")
    }

    @Test
    fun `demo login is refused while registration is closed`() {
        val response = rest.postForEntity("/api/auth/demo-login", null, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body).contains("REGISTRATION_CLOSED")
    }
}
