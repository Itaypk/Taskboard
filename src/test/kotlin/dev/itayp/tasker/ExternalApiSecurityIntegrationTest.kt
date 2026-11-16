package dev.itayp.tasker

import dev.itayp.tasker.config.DEV_USER_ID
import dev.itayp.tasker.jpa.ApiTokenScope
import dev.itayp.tasker.service.ApiTokenService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Pins the isolation between the two authentication surfaces. The valuable assertions here are
 * the negative ones: a session cookie must not work on the `/api/external` chain, and a bearer
 * token must not work on `/api/v1`. Those two properties are what keep a leaked token's blast
 * radius bounded to task content and the weekly plan.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
class ExternalApiSecurityIntegrationTest(
    @Autowired val rest: TestRestTemplate,
    @Autowired val apiTokenService: ApiTokenService,
    @Autowired val jdbcTemplate: JdbcTemplate,
) {

    private fun devUserId(): UUID = DEV_USER_ID

    /**
     * Every test here mints against the same dev user, and `ApiTokenService` caps live tokens per
     * user — without this, whichever tests ran last would fail on the cap rather than on what they
     * assert. Deleting rows directly (not revoking) also keeps `listTokens` clean between tests.
     */
    @AfterEach
    fun clearTokens() {
        jdbcTemplate.update("DELETE FROM api_token WHERE user_id = ?", DEV_USER_ID)
    }

    private fun mintToken(scope: String = ApiTokenScope.WRITE): String =
        apiTokenService.createToken(devUserId(), "integration-test", scope).plaintext

    private fun bearer(token: String) = HttpHeaders().apply {
        add(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    private fun sessionHeaders(): HttpHeaders {
        val setCookies = rest.postForEntity("/api/auth/dev-login", null, String::class.java)
            .headers[HttpHeaders.SET_COOKIE] ?: emptyList()
        val sessionCookie = setCookies.first { it.startsWith("SESSION=") }.substringBefore(";")
        val xsrfCookie = setCookies.firstOrNull { it.startsWith("XSRF-TOKEN=") }?.substringBefore(";")
        return HttpHeaders().apply {
            add(HttpHeaders.COOKIE, listOfNotNull(sessionCookie, xsrfCookie).joinToString("; "))
        }
    }

    private fun get(path: String, headers: HttpHeaders) =
        rest.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)

    // --- The isolation guarantees ---

    @Test
    fun `no token is a 401`() {
        assertThat(rest.getForEntity("/api/external/v1/tasks", String::class.java).statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `an unknown token is a 401`() {
        assertThat(get("/api/external/v1/tasks", bearer("blf_notarealtoken")).statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `a session cookie alone cannot authenticate the external API`() {
        val response = get("/api/external/v1/tasks", sessionHeaders())

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `a bearer token cannot authenticate the session API`() {
        val response = get("/api/v1/boards", bearer(mintToken()))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `a revoked token stops working immediately`() {
        val userId = devUserId()
        val created = apiTokenService.createToken(userId, "to-revoke", ApiTokenScope.WRITE)
        assertThat(get("/api/external/v1/tasks", bearer(created.plaintext)).statusCode)
            .isEqualTo(HttpStatus.OK)

        apiTokenService.revokeToken(userId, created.token.id!!)

        assertThat(get("/api/external/v1/tasks", bearer(created.plaintext)).statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `an expired token stops working and no longer counts toward the cap`() {
        val userId = devUserId()
        val tokens = (1..ApiTokenService.MAX_LIVE_TOKENS_PER_USER).map {
            apiTokenService.createToken(userId, "expiring-$it", ApiTokenScope.WRITE, Duration.ofDays(30))
        }
        // Backdate rather than wait: the expiry check is against the real clock.
        jdbcTemplate.update(
            "UPDATE api_token SET expires_at = ? WHERE user_id = ?",
            Timestamp.from(Instant.now().minusSeconds(60)), userId,
        )

        assertThat(get("/api/external/v1/tasks", bearer(tokens.first().plaintext)).statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
        // The cap counts usable tokens only, so a full set of expired ones doesn't block a replacement.
        assertThat(mintToken()).startsWith(ApiTokenService.TOKEN_PREFIX)
    }

    @Test
    fun `the plaintext token is never stored`() {
        val plaintext = mintToken()

        val hits = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM api_token WHERE token_hash = ?", Int::class.java, plaintext,
        )
        assertThat(hits).isZero()
    }

    // --- Working requests ---

    @Test
    fun `a valid token authenticates without any CSRF header`() {
        val response = get("/api/external/v1/tasks", bearer(mintToken()))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"tasks\"")
    }

    @Test
    fun `a write token can create a task with only a title`() {
        val headers = bearer(mintToken()).apply { contentType = MediaType.APPLICATION_JSON }

        val response = rest.exchange(
            "/api/external/v1/tasks", HttpMethod.POST,
            HttpEntity("""{"title":"From the external API"}""", headers), String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.body).contains("From the external API")
    }

    // --- Scope enforcement ---

    @Test
    fun `a read token may read but not write`() {
        val headers = bearer(mintToken(ApiTokenScope.READ))

        assertThat(get("/api/external/v1/tasks", headers).statusCode).isEqualTo(HttpStatus.OK)

        val write = rest.exchange(
            "/api/external/v1/tasks", HttpMethod.POST,
            HttpEntity(
                """{"title":"Should be refused"}""",
                HttpHeaders().apply {
                    addAll(headers)
                    contentType = MediaType.APPLICATION_JSON
                },
            ),
            String::class.java,
        )
        assertThat(write.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a write token may read plans but not change them, and is told which scope would`() {
        val headers = bearer(mintToken(ApiTokenScope.WRITE)).apply { contentType = MediaType.APPLICATION_JSON }

        assertThat(get("/api/external/v1/plans/week/current", headers).statusCode).isEqualTo(HttpStatus.OK)

        val put = rest.exchange(
            "/api/external/v1/plans/week/next", HttpMethod.PUT,
            HttpEntity("""{"tasks":[]}""", headers), String::class.java,
        )
        assertThat(put.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(put.body).contains("scope: plan")

        val cancel = rest.exchange(
            "/api/external/v1/events/${UUID.randomUUID()}/cancel", HttpMethod.POST,
            HttpEntity<Void>(headers), String::class.java,
        )
        assertThat(cancel.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a read token may read plans`() {
        assertThat(get("/api/external/v1/plans/week/current", bearer(mintToken(ApiTokenScope.READ))).statusCode)
            .isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `a plan token includes write and can submit a plan end to end`() {
        val headers = bearer(mintToken(ApiTokenScope.PLAN)).apply { contentType = MediaType.APPLICATION_JSON }
        assertThat(get("/api/external/v1/me", headers).body).contains("\"tokenScope\":\"plan\"")

        val created = rest.exchange(
            "/api/external/v1/tasks", HttpMethod.POST,
            HttpEntity("""{"title":"Planned from outside"}""", headers), String::class.java,
        )
        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        val taskId = Regex("\"id\":\"([0-9a-f-]{36})\"").find(created.body!!)!!.groupValues[1]

        // "next" keeps this test off the current week, which other dev-profile tests may read.
        val week = get("/api/external/v1/plans/week/next", headers).body!!
        val weekStart = Regex("\"weekStart\":\"([0-9-]{10})\"").find(week)!!.groupValues[1]
        val zone = java.time.ZoneId.of(Regex("\"timeZone\":\"([^\"]+)\"").find(week)!!.groupValues[1])
        val start = java.time.LocalDate.parse(weekStart).atTime(10, 0).atZone(zone).toOffsetDateTime()

        val put = rest.exchange(
            "/api/external/v1/plans/week/$weekStart", HttpMethod.PUT,
            HttpEntity(
                """{"tasks":[{"taskId":"$taskId","slots":[{"start":"$start","end":"${start.plusHours(1)}"}]}],"note":"Test week"}""",
                headers,
            ),
            String::class.java,
        )
        assertThat(put.statusCode).isIn(HttpStatus.CREATED, HttpStatus.OK)
        assertThat(put.body).contains("Planned from outside").contains("\"weekNote\"")

        val read = get("/api/external/v1/plans/week/$weekStart", headers)
        assertThat(read.body).contains(taskId)

        // Clearing the plan again also exercises the revise path.
        val cleared = rest.exchange(
            "/api/external/v1/plans/week/$weekStart", HttpMethod.PUT,
            HttpEntity("""{"tasks":[]}""", headers), String::class.java,
        )
        assertThat(cleared.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(cleared.body).doesNotContain(taskId)
    }

    @Test
    fun `token management is not reachable with a token`() {
        // Minting must require a browser session, or a leaked token could mint its own successors.
        val response = get("/api/v1/api-tokens", bearer(mintToken()))

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
