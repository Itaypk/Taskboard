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
    fun `PRINCIPAL_NAME is populated with the user id so sessions can be found per user`() {
        // The whole active-sessions feature is built on findByPrincipalName. SessionAuthenticator
        // stamps the index attribute explicitly; this pins that it actually reaches the column.
        val login = rest.postForEntity("/api/auth/dev-login", null, String::class.java)
        assertThat(login.statusCode).isEqualTo(HttpStatus.OK)

        val principalNames = jdbcTemplate.queryForList(
            "SELECT PRINCIPAL_NAME FROM SPRING_SESSION", String::class.java,
        )
        val devUserId = java.util.UUID.nameUUIDFromBytes("tasker-dev-user".toByteArray()).toString()
        assertThat(principalNames).contains(devUserId)
    }

    @Test
    fun `active sessions lists every login and revoke-others leaves only the current one`() {
        // Two dev-logins are two sessions for the same (deterministic) dev user.
        val first = sessionHeaders(devLoginCookies())
        val second = devLoginCookies()
        val secondHeaders = sessionHeaders(second)

        val listed = rest.exchange(
            "/api/auth/sessions", HttpMethod.GET, HttpEntity<Void>(secondHeaders), String::class.java,
        )
        assertThat(listed.statusCode).isEqualTo(HttpStatus.OK)
        // Exactly one entry is flagged as the requesting device, whatever else is in the list.
        assertThat(Regex("\"current\":true").findAll(listed.body!!).count()).isEqualTo(1)
        assertThat(listed.body).doesNotContain("sessionId")

        val xsrf = second.first { it.startsWith("XSRF-TOKEN=") }
            .substringAfter("XSRF-TOKEN=").substringBefore(";")
        val revokeHeaders = sessionHeaders(second).apply { add("X-XSRF-TOKEN", xsrf) }
        val revoked = rest.exchange(
            "/api/auth/sessions/revoke-others", HttpMethod.POST, HttpEntity<Void>(revokeHeaders), String::class.java,
        )
        assertThat(revoked.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(revoked.body).contains("\"revoked\"")

        // The revoking session still works; the other one is gone.
        val stillAlive = rest.exchange(
            "/api/auth/sessions", HttpMethod.GET, HttpEntity<Void>(secondHeaders), String::class.java,
        )
        assertThat(stillAlive.statusCode).isEqualTo(HttpStatus.OK)

        val killed = rest.exchange(
            "/api/v1/boards", HttpMethod.GET, HttpEntity<Void>(first), String::class.java,
        )
        assertThat(killed.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `active sessions requires authentication`() {
        val response = rest.getForEntity("/api/auth/sessions", String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `revoke-others without a CSRF token returns 403`() {
        // Unlike the login endpoints, this one is not CSRF-exempt: the user already has a session.
        val headers = sessionHeaders(devLoginCookies())
        val response = rest.exchange(
            "/api/auth/sessions/revoke-others", HttpMethod.POST, HttpEntity<Void>(headers), String::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    private fun devLoginCookies(): List<String> =
        rest.postForEntity("/api/auth/dev-login", null, String::class.java)
            .headers[HttpHeaders.SET_COOKIE] ?: emptyList()

    @Test
    fun `the app layer does not emit a Content-Security-Policy header`() {
        // CSP is owned entirely by Nginx (per-vhost, in the itayp_dev Ansible repo), not the app:
        // Spring's HeaderWriterFilter can't cover the forwarded SPA document anyway, and a second
        // app-layer CSP behind Nginx would produce a duplicate header. If this starts failing,
        // someone re-added CSP to SecurityConfiguration — remove it; the policy belongs in Nginx.
        val response = rest.getForEntity("/api/v1/boards", String::class.java)
        assertThat(response.headers.getFirst("Content-Security-Policy")).isNull()
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

    /**
     * In production the app sits behind a TLS-terminating proxy and is reached over plain HTTP, so
     * the flag can't come from `request.isSecure()`: the `prod` profile has to force it.
     */
    @Test
    fun `the session cookie is Secure even when the app itself is reached over plain HTTP`() {
        val response = rest.postForEntity("/api/auth/demo-login", null, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val sessionCookie = response.headers[HttpHeaders.SET_COOKIE].orEmpty().first { it.startsWith("SESSION=") }
        assertThat(sessionCookie.split(";").map { it.trim() }).contains("Secure", "HttpOnly", "SameSite=Lax")
    }
}
