package dev.itayp.tasker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
class SecurityIntegrationTest(
    @Autowired val rest: TestRestTemplate,
    @Autowired val jdbcTemplate: JdbcTemplate,
) {

    @Test
    fun `unauth request to tasks returns 401`() {
        val response = rest.getForEntity("/api/v1/tasks", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `dev login issues a session cookie that authenticates subsequent requests`() {
        val login = rest.postForEntity("/api/auth/dev-login", null, String::class.java)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        val setCookies = login.headers[HttpHeaders.SET_COOKIE] ?: emptyList()
        assertThat(setCookies).anyMatch { it.startsWith("JSESSIONID=") }

        val sessionCookie = setCookies.first { it.startsWith("JSESSIONID=") }.substringBefore(";")
        val xsrfCookie = setCookies.firstOrNull { it.startsWith("XSRF-TOKEN=") }?.substringBefore(";")
        val headers = HttpHeaders().apply {
            add(HttpHeaders.COOKIE, listOfNotNull(sessionCookie, xsrfCookie).joinToString("; "))
        }

        val tasks = rest.exchange("/api/v1/tasks", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertThat(tasks.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `session is persisted in the SPRING_SESSION table and last-access time rolls forward on activity`() {
        val login = rest.postForEntity("/api/auth/dev-login", null, String::class.java)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        val setCookies = login.headers[HttpHeaders.SET_COOKIE] ?: emptyList()
        val sessionCookie = setCookies.first { it.startsWith("JSESSIONID=") }.substringBefore(";")
        val xsrfCookie = setCookies.firstOrNull { it.startsWith("XSRF-TOKEN=") }?.substringBefore(";")

        // A row should now exist in SPRING_SESSION — this is what makes sessions survive restart.
        val rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Int::class.java)
        assertThat(rowCount).isGreaterThanOrEqualTo(1)

        val initialLastAccess = jdbcTemplate.queryForObject(
            "SELECT MAX(LAST_ACCESS_TIME) FROM SPRING_SESSION", Long::class.java,
        )!!

        // H2's time source has ms granularity; sleep long enough to guarantee a strictly-greater tick.
        Thread.sleep(20)

        val headers = HttpHeaders().apply {
            add(HttpHeaders.COOKIE, listOfNotNull(sessionCookie, xsrfCookie).joinToString("; "))
        }
        val tasks = rest.exchange("/api/v1/tasks", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertThat(tasks.statusCode).isEqualTo(HttpStatus.OK)

        val updatedLastAccess = jdbcTemplate.queryForObject(
            "SELECT MAX(LAST_ACCESS_TIME) FROM SPRING_SESSION", Long::class.java,
        )!!
        assertThat(updatedLastAccess).isGreaterThan(initialLastAccess)
    }

    @Test
    fun `mutating request without CSRF token returns 403`() {
        val login = rest.postForEntity("/api/auth/dev-login", null, String::class.java)
        val sessionCookie = login.headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("JSESSIONID=") }
            .substringBefore(";")

        val headers = HttpHeaders().apply {
            add(HttpHeaders.COOKIE, sessionCookie)
            contentType = MediaType.APPLICATION_JSON
        }
        val body = """{"title":"x","status":"todo","categoryId":"00000000-0000-0000-0000-000000000001","tags":[]}"""
        val response = rest.exchange(
            "/api/v1/tasks", HttpMethod.POST, HttpEntity(body, headers), String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("prod")
@ContextConfiguration(initializers = [AbstractIntegrationTest.Initializer::class])
class SecurityIntegrationProdProfileTest(@Autowired val rest: TestRestTemplate) {

    @Test
    fun `dev-login endpoint is not registered under prod profile`() {
        val response = rest.postForEntity("/api/auth/dev-login", null, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
