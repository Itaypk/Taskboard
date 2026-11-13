package dev.itayp.tasker.calendar

import dev.itayp.tasker.config.DEV_USER_ID
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
import java.net.URI

/**
 * The feed's security chain and HTTP contract: reachable without a session, never creating one,
 * a uniform 404 for unknown links, conditional GET, and an empty calendar (not an error) once the
 * user has switched away from the feed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("dev")
class CalendarFeedIntegrationTest(
    @Autowired val rest: TestRestTemplate,
    @Autowired val calendarDeliveryService: CalendarDeliveryService,
    @Autowired val calendarFeedService: CalendarFeedService,
    @Autowired val jdbcTemplate: JdbcTemplate,
) {

    @AfterEach
    fun reset() {
        jdbcTemplate.update("DELETE FROM calendar_feed WHERE user_id = ?", DEV_USER_ID)
        jdbcTemplate.update(
            "UPDATE user_settings SET calendar_delivery = 'NONE', calendar_invite_email = FALSE WHERE user_id = ?",
            DEV_USER_ID,
        )
    }

    private fun feedPath(): String {
        val state = calendarDeliveryService.change(DEV_USER_ID, CalendarDelivery.FEED)
        return URI(state.feed!!.url).path
    }

    private fun get(path: String, headers: HttpHeaders = HttpHeaders()) =
        rest.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)

    @Test
    fun `serves the feed without a session and never sets a cookie`() {
        val response = get(feedPath())

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType?.isCompatibleWith(MediaType("text", "calendar"))).isTrue()
        assertThat(response.body).startsWith("BEGIN:VCALENDAR\r\n").contains("METHOD:PUBLISH")
        assertThat(response.headers[HttpHeaders.SET_COOKIE]).isNullOrEmpty()
    }

    @Test
    fun `answers 304 when the calendar app already has the current version`() {
        val path = feedPath()
        val etag = get(path).headers.eTag

        val headers = HttpHeaders().apply { ifNoneMatch = listOf(etag!!) }
        assertThat(get(path, headers).statusCode).isEqualTo(HttpStatus.NOT_MODIFIED)
    }

    @Test
    fun `stamps the first fetch so Settings can show the subscription works`() {
        get(feedPath())

        assertThat(calendarDeliveryService.getState(DEV_USER_ID).feed!!.lastFetchedAt).isNotNull()
    }

    @Test
    fun `an unknown or malformed link is a plain 404`() {
        assertThat(get("/calendar/${"A".repeat(43)}.ics").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/calendar/nope.ics").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a replaced link stops working at once`() {
        val old = feedPath()
        val new = URI(calendarFeedService.rotate(DEV_USER_ID).url).path

        assertThat(get(old).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get(new).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `switching away keeps the link alive but serves an empty calendar`() {
        val path = feedPath()
        calendarDeliveryService.change(DEV_USER_ID, CalendarDelivery.NONE)

        val response = get(path)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("BEGIN:VCALENDAR").doesNotContain("BEGIN:VEVENT")
    }

    @Test
    fun `the settings API switches delivery with a session and CSRF`() {
        val login = rest.postForEntity("/api/auth/dev-login", null, String::class.java)
        val setCookies = login.headers[HttpHeaders.SET_COOKIE] ?: emptyList()
        val session = setCookies.first { it.startsWith("SESSION=") }.substringBefore(";")
        // The CSRF cookie is issued on the first request that needs it.
        val probe = rest.exchange(
            "/api/v1/calendar-delivery", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, session) }), String::class.java,
        )
        assertThat(probe.statusCode).isEqualTo(HttpStatus.OK)
        val xsrf = (setCookies + (probe.headers[HttpHeaders.SET_COOKIE] ?: emptyList()))
            .first { it.startsWith("XSRF-TOKEN=") }.substringBefore(";")

        val headers = HttpHeaders().apply {
            add(HttpHeaders.COOKIE, "$session; $xsrf")
            add("X-XSRF-TOKEN", xsrf.substringAfter("="))
            contentType = MediaType.APPLICATION_JSON
        }
        val response = rest.exchange(
            "/api/v1/calendar-delivery", HttpMethod.PUT,
            HttpEntity("""{"delivery":"FEED"}""", headers), String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"delivery\":\"FEED\"").contains("/calendar/").contains("webcal://")
    }
}
