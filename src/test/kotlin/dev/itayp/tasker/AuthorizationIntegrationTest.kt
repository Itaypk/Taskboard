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
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Cross-user authorization (IDOR) coverage for the board-scoped content API. The threat model:
 * an authenticated user who is *not* a member of a board must never read or mutate that board's
 * tasks, categories, tags, or the board itself — whether the board exists (someone else's) or not.
 *
 * Every board-scoped service method opens with `BoardMembershipService.requireMember`, which throws
 * `BoardAccessDeniedException` → 403. These tests exercise the full HTTP stack (security filters,
 * CSRF, routing, the membership guard) so a regression that drops `requireMember` from any endpoint
 * surfaces as a 200/201/204 instead of the expected 403.
 *
 * Two independent identities are used: the deterministic dev user (the "attacker") and an ephemeral
 * demo user (the "victim"), each auto-provisioned with exactly one board.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
class AuthorizationIntegrationTest(@Autowired val rest: TestRestTemplate) {

    /** A logged-in identity: the cookie header to replay plus the raw CSRF token to echo on writes. */
    private data class Session(val cookieHeader: String, val xsrfToken: String)

    @Test
    fun `a non-member cannot read or mutate another user's board`() {
        val attacker = login("/api/auth/dev-login")
        val victim = login("/api/auth/demo-login")
        val victimBoard = soleBoardId(victim)

        // Positive control: the attacker's session+CSRF headers are valid, proving the 403s below are
        // authorization failures and not CSRF rejections (both share status 403).
        val ownBoard = soleBoardId(attacker)
        val ownCategory = firstCategoryId(attacker, ownBoard)
        val createOnOwn = send(
            attacker, HttpMethod.POST, "/api/v1/boards/$ownBoard/tasks",
            """{"title":"sanity","categoryId":"$ownCategory"}""",
        )
        assertThat(createOnOwn.statusCode)
            .withFailMessage("control failed — attacker cannot write to own board, so cross-board 403s are inconclusive: %s", createOnOwn.body)
            .isEqualTo(HttpStatus.CREATED)

        forEachBoardScopedEndpoint(victimBoard) { method, path, body ->
            val response = send(attacker, method, path, body)
            assertThat(response.statusCode)
                .withFailMessage("$method $path leaked access (expected 403, got ${response.statusCode})")
                .isEqualTo(HttpStatus.FORBIDDEN)
        }
    }

    @Test
    fun `a request against a non-existent board is 403, not 404 (no existence leak)`() {
        val attacker = login("/api/auth/dev-login")
        val ghostBoard = UUID.randomUUID()

        // A uniform 403 whether or not the board exists prevents probing board ids for existence.
        forEachBoardScopedEndpoint(ghostBoard) { method, path, body ->
            val response = send(attacker, method, path, body)
            assertThat(response.statusCode)
                .withFailMessage("$method $path returned ${response.statusCode}; expected 403 to avoid leaking board existence")
                .isEqualTo(HttpStatus.FORBIDDEN)
        }
    }

    /**
     * Drives [block] over every board-scoped endpoint for [boardId]. Sub-resource ids are random:
     * the membership guard runs before the resource is loaded, so a non-member is rejected before the
     * id is ever resolved. Bodies are schema-valid so bean validation can't pre-empt the guard with a 400.
     */
    private fun forEachBoardScopedEndpoint(boardId: UUID, block: (HttpMethod, String, String?) -> Unit) {
        val taskId = UUID.randomUUID()
        val categoryId = UUID.randomUUID()
        val base = "/api/v1/boards/$boardId"
        val taskBody = """{"title":"x","categoryId":"$categoryId"}"""
        val categoryBody = """{"label":"x","swatchId":"slate"}"""

        val endpoints = listOf<Triple<HttpMethod, String, String?>>(
            // Tasks
            Triple(HttpMethod.GET, "$base/tasks", null),
            Triple(HttpMethod.POST, "$base/tasks", taskBody),
            Triple(HttpMethod.PUT, "$base/tasks/$taskId", taskBody),
            Triple(HttpMethod.DELETE, "$base/tasks/$taskId", null),
            Triple(HttpMethod.DELETE, "$base/tasks/tutorial", null),
            Triple(HttpMethod.POST, "$base/tasks/$taskId/duplicate", null),
            Triple(HttpMethod.POST, "$base/tasks/$taskId/move", """{"targetBoardId":"${UUID.randomUUID()}","categoryId":"${UUID.randomUUID()}"}"""),
            Triple(HttpMethod.PUT, "$base/tasks/$taskId/assignee", """{"userId":null}"""),
            Triple(HttpMethod.PATCH, "$base/tasks/$taskId/reorder", """{"afterId":null,"beforeId":null}"""),
            Triple(HttpMethod.DELETE, "$base/tasks/$taskId/plan-schedule", null),
            // Categories
            Triple(HttpMethod.GET, "$base/categories", null),
            Triple(HttpMethod.POST, "$base/categories", categoryBody),
            Triple(HttpMethod.PUT, "$base/categories/$categoryId", categoryBody),
            Triple(HttpMethod.DELETE, "$base/categories/$categoryId", null),
            // Tags
            Triple(HttpMethod.GET, "$base/tags", null),
            // The board resource itself
            Triple(HttpMethod.PATCH, base, """{"name":"hijacked"}"""),
            Triple(HttpMethod.DELETE, base, null),
        )

        endpoints.forEach { (method, path, body) -> block(method, path, body) }
    }

    private fun login(path: String): Session {
        val response = rest.postForEntity(path, null, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val setCookies = response.headers[HttpHeaders.SET_COOKIE] ?: emptyList()
        val session = setCookies.first { it.startsWith("SESSION=") }.substringBefore(";")
        val xsrfCookie = setCookies.first { it.startsWith("XSRF-TOKEN=") }.substringBefore(";")
        return Session(
            cookieHeader = "$session; $xsrfCookie",
            xsrfToken = xsrfCookie.substringAfter("="),
        )
    }

    private fun send(session: Session, method: HttpMethod, path: String, body: String?): org.springframework.http.ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            add(HttpHeaders.COOKIE, session.cookieHeader)
            if (method != HttpMethod.GET) {
                add("X-XSRF-TOKEN", session.xsrfToken)
                contentType = MediaType.APPLICATION_JSON
            }
        }
        return rest.exchange(path, method, HttpEntity(body, headers), String::class.java)
    }

    private fun soleBoardId(session: Session): UUID {
        val boards = send(session, HttpMethod.GET, "/api/v1/boards", null)
        assertThat(boards.statusCode).isEqualTo(HttpStatus.OK)
        val match = Regex("\"id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").find(boards.body ?: "")
        assertThat(match).withFailMessage("no board id in response: ${boards.body}").isNotNull()
        return UUID.fromString(match!!.groupValues[1])
    }

    private fun firstCategoryId(session: Session, boardId: UUID): UUID {
        val categories = send(session, HttpMethod.GET, "/api/v1/boards/$boardId/categories", null)
        assertThat(categories.statusCode).isEqualTo(HttpStatus.OK)
        val match = Regex("\"id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").find(categories.body ?: "")
        assertThat(match).withFailMessage("no category id in response: ${categories.body}").isNotNull()
        return UUID.fromString(match!!.groupValues[1])
    }
}
