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
    fun `unauth request to boards returns 401`() {
        val response = rest.getForEntity("/api/v1/boards", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `dev login issues a session cookie that authenticates subsequent requests`() {
        val login = rest.postForEntity("/api/auth/dev-login", null, String::class.java)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        val setCookies = login.headers[HttpHeaders.SET_COOKIE] ?: emptyList()
        assertThat(setCookies).anyMatch { it.startsWith("SESSION=") }

        val headers = sessionHeaders(setCookies)
        val boardId = fetchSoleBoardId(headers)

        val tasks = rest.exchange(
            "/api/v1/boards/$boardId/tasks", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java,
        )
        assertThat(tasks.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `accessing another user's board returns 403`() {
        // Two independent identities: the dev user and an ephemeral demo user, each with one board.
        val devHeaders = sessionHeaders(
            rest.postForEntity("/api/auth/dev-login", null, String::class.java)
                .headers[HttpHeaders.SET_COOKIE] ?: emptyList(),
        )
        val demoHeaders = sessionHeaders(
            rest.postForEntity("/api/auth/demo-login", null, String::class.java)
                .headers[HttpHeaders.SET_COOKIE] ?: emptyList(),
        )
        val demoBoardId = fetchSoleBoardId(demoHeaders)

        val response = rest.exchange(
            "/api/v1/boards/$demoBoardId/tasks", HttpMethod.GET, HttpEntity<Void>(devHeaders), String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    private fun sessionHeaders(setCookies: List<String>): HttpHeaders {
        val sessionCookie = setCookies.first { it.startsWith("SESSION=") }.substringBefore(";")
        val xsrfCookie = setCookies.firstOrNull { it.startsWith("XSRF-TOKEN=") }?.substringBefore(";")
        return HttpHeaders().apply {
            add(HttpHeaders.COOKIE, listOfNotNull(sessionCookie, xsrfCookie).joinToString("; "))
        }
    }

    /** Pulls the (sole) board id out of the GET /api/v1/boards JSON without a full DTO. */
    private fun fetchSoleBoardId(headers: HttpHeaders): String {
        val boards = rest.exchange("/api/v1/boards", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertThat(boards.statusCode).isEqualTo(HttpStatus.OK)
        val match = Regex("\"id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").find(boards.body ?: "")
        assertThat(match).withFailMessage("no board id in response: ${boards.body}").isNotNull()
        return match!!.groupValues[1]
    }

    @Test
    fun `session is persisted in the SPRING_SESSION table and last-access time rolls forward on activity`() {
        val login = rest.postForEntity("/api/auth/dev-login", null, String::class.java)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)
        val setCookies = login.headers[HttpHeaders.SET_COOKIE] ?: emptyList()
        val sessionCookie = setCookies.first { it.startsWith("SESSION=") }.substringBefore(";")
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
        val boards = rest.exchange("/api/v1/boards", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertThat(boards.statusCode).isEqualTo(HttpStatus.OK)

        val updatedLastAccess = jdbcTemplate.queryForObject(
            "SELECT MAX(LAST_ACCESS_TIME) FROM SPRING_SESSION", Long::class.java,
        )!!
        assertThat(updatedLastAccess).isGreaterThan(initialLastAccess)
    }

    @Test
    fun `responses include a Content-Security-Policy header`() {
        val response = rest.getForEntity("/api/v1/boards", String::class.java)
        // Doesn't matter that this is a 401 — CSP should land regardless.
        val csp = response.headers.getFirst("Content-Security-Policy")
        assertThat(csp).isNotNull()
        assertThat(csp).contains("default-src 'self'")
        assertThat(csp).contains("script-src 'self'")
        assertThat(csp).contains("frame-ancestors 'none'")
        // Telegram login is now a top-level OAuth redirect, so the widget's script/frame
        // allowances are gone.
        assertThat(csp).doesNotContain("telegram.org")
    }

    @Test
    fun `login rotates the session id (session-fixation defense)`() {
        // First login creates session A.
        val first = rest.postForEntity("/api/auth/dev-login", null, String::class.java)
        val firstSessionCookie = first.headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("SESSION=") }
            .substringBefore(";")

        // Second login while presenting session A's cookie. SessionAuthenticator should call
        // request.changeSessionId(), so the response must set a different SESSION value.
        val headers = HttpHeaders().apply { add(HttpHeaders.COOKIE, firstSessionCookie) }
        val second = rest.exchange(
            "/api/auth/dev-login", HttpMethod.POST, HttpEntity<Void>(headers), String::class.java,
        )
        assertThat(second.statusCode).isEqualTo(HttpStatus.OK)
        val secondSessionCookie = second.headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("SESSION=") }
            .substringBefore(";")
        assertThat(secondSessionCookie).isNotEqualTo(firstSessionCookie)
    }

    @Test
    fun `mutating request without CSRF token returns 403`() {
        val login = rest.postForEntity("/api/auth/dev-login", null, String::class.java)
        val sessionCookie = login.headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("SESSION=") }
            .substringBefore(";")

        val headers = HttpHeaders().apply {
            add(HttpHeaders.COOKIE, sessionCookie)
            contentType = MediaType.APPLICATION_JSON
        }
        val body = """{"title":"x","status":"todo","categoryId":"00000000-0000-0000-0000-000000000001","tags":[]}"""
        // CSRF is rejected before routing, so a placeholder board id is fine here.
        val response = rest.exchange(
            "/api/v1/boards/00000000-0000-0000-0000-000000000002/tasks",
            HttpMethod.POST, HttpEntity(body, headers), String::class.java,
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
