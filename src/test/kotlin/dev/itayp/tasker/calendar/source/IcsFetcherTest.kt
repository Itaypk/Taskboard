package dev.itayp.tasker.calendar.source

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.itayp.tasker.config.AppProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IcsFetcherTest {

    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<HttpExchange>()
    private val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n".toByteArray()

    private val loopback = InetAddress.getByName("127.0.0.1")

    /** Only 127.0.0.1 counts as "public" here, so the server is reachable and 127.0.0.2 is not. */
    private fun fetcher(
        limits: IcsFetcher.Limits = IcsFetcher.Limits(),
        allowed: (InetAddress) -> Boolean = { it == loopback },
    ) = IcsFetcher(AppProperties(), limits, allowed, requireHttps = false)

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun route(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            requests += exchange
            exchange.use(handler)
        }
    }

    private fun HttpExchange.respond(status: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.write(body)
    }

    private fun fetchError(uri: String, f: IcsFetcher = fetcher(), policy: IcsFetcher.AddressPolicy = IcsFetcher.AddressPolicy.USER_SUPPLIED) =
        assertFailsWith<IcsFetchException> { f.fetch(URI(uri), policy) }.error

    @Test
    fun `fetches a calendar and returns its validators`() {
        route("/cal.ics") { it.respond(200, ics, mapOf("ETag" to "\"v1\"", "Last-Modified" to "Wed, 25 Nov 2026 10:00:00 GMT")) }

        val result = fetcher().fetch(URI("$base/cal.ics"), IcsFetcher.AddressPolicy.USER_SUPPLIED)

        val fetched = assertIs<IcsFetcher.Result.Fetched>(result)
        assertContentEquals(ics, fetched.body)
        assertEquals(IcsFetcher.Validators("\"v1\"", "Wed, 25 Nov 2026 10:00:00 GMT"), fetched.validators)
        assertTrue(requests.single().requestHeaders.getFirst("User-Agent").contains("calendar reader"))
        assertEquals(1, requests.size)
    }

    @Test
    fun `sends validators and understands 304`() {
        route("/cal.ics") { it.respond(304) }

        val result = fetcher().fetch(URI("$base/cal.ics"), IcsFetcher.AddressPolicy.USER_SUPPLIED, IcsFetcher.Validators("\"v1\"", null))

        assertEquals(IcsFetcher.Result.NotModified, result)
        assertEquals("\"v1\"", requests.single().requestHeaders.getFirst("If-None-Match"))
        assertNull(requests.single().requestHeaders.getFirst("If-Modified-Since"))
    }

    @Test
    fun `accepts a BOM and any content type`() {
        route("/cal.ics") { it.respond(200, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "\r\nbegin:vcalendar\r\n".toByteArray(), mapOf("Content-Type" to "application/octet-stream")) }

        assertIs<IcsFetcher.Result.Fetched>(fetcher().fetch(URI("$base/cal.ics"), IcsFetcher.AddressPolicy.USER_SUPPLIED))
    }

    @Test
    fun `maps statuses to codes`() {
        route("/gone") { it.respond(410) }
        route("/missing") { it.respond(404) }
        route("/private") { it.respond(403) }
        route("/broken") { it.respond(500) }
        route("/page") { it.respond(200, "<!doctype html><html>Calendar</html>".toByteArray(), mapOf("Content-Type" to "text/html")) }
        route("/empty") { it.respond(200) }

        assertEquals(IcsFetchError.NOT_FOUND, fetchError("$base/gone"))
        assertEquals(IcsFetchError.NOT_FOUND, fetchError("$base/missing"))
        assertEquals(IcsFetchError.UNAUTHORIZED, fetchError("$base/private"))
        assertEquals(IcsFetchError.UNREACHABLE, fetchError("$base/broken"))
        assertEquals(IcsFetchError.NOT_A_CALENDAR, fetchError("$base/page"))
        assertEquals(IcsFetchError.NOT_A_CALENDAR, fetchError("$base/empty"))
    }

    @Test
    fun `refuses bodies over the cap, whether or not the length is declared`() {
        val big = ics + ByteArray(2048) { 'x'.code.toByte() }
        route("/declared") { it.respond(200, big) }
        route("/chunked") { ex ->
            ex.sendResponseHeaders(200, 0) // chunked: no declared length
            ex.responseBody.write(big)
        }
        val small = fetcher(IcsFetcher.Limits(maxBytes = 1024))

        assertEquals(IcsFetchError.TOO_LARGE, fetchError("$base/declared", small))
        assertEquals(IcsFetchError.TOO_LARGE, fetchError("$base/chunked", small))
    }

    @Test
    fun `follows redirects, dropping validators on the way`() {
        route("/old") { it.respond(301, headers = mapOf("Location" to "/new")) }
        route("/new") { it.respond(200, ics) }

        val result = fetcher().fetch(URI("$base/old"), IcsFetcher.AddressPolicy.USER_SUPPLIED, IcsFetcher.Validators("\"v1\"", null))

        assertIs<IcsFetcher.Result.Fetched>(result)
        assertEquals(listOf("/old", "/new"), requests.map { it.requestURI.path })
        assertNull(requests[1].requestHeaders.getFirst("If-None-Match"))
    }

    @Test
    fun `stops after too many redirects`() {
        route("/loop") { it.respond(302, headers = mapOf("Location" to "/loop")) }

        assertEquals(IcsFetchError.UNREACHABLE, fetchError("$base/loop"))
        assertEquals(4, requests.size)
    }

    @Test
    fun `a redirect to a blocked address is refused before connecting`() {
        route("/hop") { it.respond(302, headers = mapOf("Location" to "http://127.0.0.2:${server.address.port}/cal.ics")) }

        assertEquals(IcsFetchError.BLOCKED_ADDRESS, fetchError("$base/hop"))
    }

    @Test
    fun `a redirect off https is refused`() {
        route("/hop") { it.respond(302, headers = mapOf("Location" to "ftp://example.com/cal.ics")) }

        assertEquals(IcsFetchError.NOT_HTTPS, fetchError("$base/hop"))
    }

    @Test
    fun `the real guard blocks loopback, by name or literal`() {
        route("/cal.ics") { it.respond(200, ics) }
        val guarded = fetcher(allowed = PublicAddresses::isPublic)

        assertEquals(IcsFetchError.BLOCKED_ADDRESS, fetchError("$base/cal.ics", guarded))
        assertEquals(IcsFetchError.BLOCKED_ADDRESS, fetchError("http://localhost:${server.address.port}/cal.ics", guarded))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `trusted fetches skip the guard`() {
        route("/cal.ics") { it.respond(200, ics) }
        val guarded = fetcher(allowed = PublicAddresses::isPublic)

        assertIs<IcsFetcher.Result.Fetched>(guarded.fetch(URI("$base/cal.ics"), IcsFetcher.AddressPolicy.TRUSTED))
    }

    @Test
    fun `allowing private addresses lets a LAN calendar through`() {
        route("/cal.ics") { it.respond(200, ics) }
        // The production constructor with the flag on; plain HTTP is refused there, so check that the
        // guard is what's out of the way: the failure is about the scheme, not the address.
        val lan = IcsFetcher(CalendarSourceProperties(allowPrivateAddresses = true), AppProperties())
        val strict = IcsFetcher(CalendarSourceProperties(), AppProperties())

        assertEquals(IcsFetchError.NOT_HTTPS, fetchError("$base/cal.ics", lan))
        assertEquals(IcsFetchError.UNREACHABLE, fetchError("https://127.0.0.1:${server.address.port}/cal.ics", lan))
        assertEquals(IcsFetchError.BLOCKED_ADDRESS, fetchError("https://127.0.0.1:${server.address.port}/cal.ics", strict))
    }

    @Test
    fun `times out on a server that doesn't answer`() {
        route("/slow") { Thread.sleep(2_000) }
        val impatient = fetcher(IcsFetcher.Limits(readTimeout = Duration.ofMillis(300), callTimeout = Duration.ofSeconds(1)))

        assertEquals(IcsFetchError.TIMEOUT, fetchError("$base/slow", impatient))
    }

    @Test
    fun `requires https in production`() {
        val production = IcsFetcher(CalendarSourceProperties(), AppProperties())

        assertEquals(IcsFetchError.NOT_HTTPS, fetchError("$base/cal.ics", production))
    }
}
