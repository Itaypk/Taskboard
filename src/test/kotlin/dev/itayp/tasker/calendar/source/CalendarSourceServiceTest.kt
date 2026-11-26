package dev.itayp.tasker.calendar.source

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.CalendarSourceEntity
import dev.itayp.tasker.repository.CalendarSourceRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CalendarSourceServiceTest {

    @Mock private lateinit var repository: CalendarSourceRepository
    @Mock private lateinit var userCryptoService: UserCryptoService
    @Mock private lateinit var fetcher: IcsFetcher

    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val now = Instant.parse("2026-11-26T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val meterRegistry = SimpleMeterRegistry()
    private var allowed = true
    private val url = "https://calendar.example.com/private-abc/basic.ics"

    private val ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:test
        BEGIN:VEVENT
        UID:one
        SUMMARY:Review
        DTSTART:20261127T090000Z
        DTEND:20261127T100000Z
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n").toByteArray()

    private lateinit var service: CalendarSourceService

    @BeforeEach
    fun setUp() {
        service = CalendarSourceService(
            repository, userCryptoService, fetcher, IcsParser(), CalendarSourceProperties(), { allowed }, meterRegistry, clock,
        )
        whenever(userCryptoService.encrypt(eq(userId), anyOrNull())).thenAnswer { (it.arguments[1] as String?)?.toByteArray() }
        whenever(userCryptoService.decrypt(eq(userId), anyOrNull())).thenAnswer { (it.arguments[1] as ByteArray?)?.let(::String) }
        whenever(repository.save(any<CalendarSourceEntity>())).thenAnswer { it.arguments[0] }
        whenever(repository.existsById(any())).thenReturn(true)
    }

    @AfterEach
    fun tearDown() = service.destroy()

    private fun fetchReturns(body: ByteArray = ics) {
        whenever(fetcher.fetch(any(), eq(IcsFetcher.AddressPolicy.USER_SUPPLIED), anyOrNull()))
            .thenReturn(IcsFetcher.Result.Fetched(body, IcsFetcher.Validators(null, null)))
    }

    private fun existing(vararg urls: String): List<CalendarSourceEntity> = urls.map { u ->
        CalendarSourceEntity().apply {
            id = UUID.randomUUID()
            userId = this@CalendarSourceServiceTest.userId
            this.url = u.toByteArray()
            label = "Work".toByteArray()
            detail = "TITLES"
            createdAt = now
        }
    }.also { whenever(repository.findAllByUserIdOrderByCreatedAtAsc(userId)).thenReturn(it) }

    @Test
    fun `adds a calendar after reading it, storing only encrypted values and the status`() {
        existing()
        fetchReturns()

        val view = service.add(userId, " webcal://calendar.example.com/private-abc/basic.ics ", null, CalendarSourceDetail.TITLES)

        val saved = argumentCaptor<CalendarSourceEntity>().also { verify(repository).save(it.capture()) }.firstValue
        assertEquals(url, String(saved.url!!))
        assertEquals("calendar.example.com", view.label) // no name given: the host
        assertEquals("calendar.example.com", view.host)
        assertEquals(1, view.lastEventCount)
        assertEquals(now, view.lastSuccessAt)
        assertNull(view.lastError)
        verify(fetcher).fetch(eq(URI(url)), eq(IcsFetcher.AddressPolicy.USER_SUPPLIED), anyOrNull())
        assertEquals(1.0, meterRegistry.counter(CalendarSourceService.FETCHES_METRIC, "outcome", "ok").count())
    }

    @Test
    fun `refuses an address that doesn't work, saving nothing`() {
        existing()
        whenever(fetcher.fetch(any(), any(), anyOrNull())).thenThrow(IcsFetchException(IcsFetchError.NOT_A_CALENDAR))

        val e = assertFailsWith<CalendarSourceException> { service.add(userId, url, "Work", CalendarSourceDetail.BUSY) }

        assertEquals("NOT_A_CALENDAR", e.code)
        verify(repository, never()).save(any<CalendarSourceEntity>())
        assertEquals(1.0, meterRegistry.counter(CalendarSourceService.FETCHES_METRIC, "outcome", "not_a_calendar").count())
    }

    @Test
    fun `refuses plain http before fetching`() {
        existing()

        assertEquals("NOT_HTTPS", assertFailsWith<CalendarSourceException> { service.add(userId, "http://example.com/a.ics", null, CalendarSourceDetail.TITLES) }.code)
        verify(fetcher, never()).fetch(any(), any(), anyOrNull())
    }

    @Test
    fun `refuses a sixth calendar and a duplicate`() {
        existing(*(1..5).map { "https://example.com/$it.ics" }.toTypedArray())
        assertEquals(CalendarSourceException.LIMIT_REACHED, assertFailsWith<CalendarSourceException> { service.add(userId, url, null, CalendarSourceDetail.TITLES) }.code)

        existing(url)
        assertEquals(CalendarSourceException.DUPLICATE, assertFailsWith<CalendarSourceException> { service.add(userId, "webcal://calendar.example.com/private-abc/basic.ics", null, CalendarSourceDetail.TITLES) }.code)
        verify(fetcher, never()).fetch(any(), any(), anyOrNull())
    }

    @Test
    fun `rate limits adding`() {
        existing()
        allowed = false

        val e = assertFailsWith<CalendarSourceException> { service.add(userId, url, null, CalendarSourceDetail.TITLES) }

        assertEquals(CalendarSourceException.RATE_LIMITED, e.code)
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.status)
    }

    @Test
    fun `cleans labels`() {
        assertEquals("Work stuff", CalendarSourceService.cleanLabel("  Work \n stuff "))
        assertNull(CalendarSourceService.cleanLabel("   "))
        assertEquals(CalendarSourceService.MAX_LABEL_LENGTH, CalendarSourceService.cleanLabel("x".repeat(100))!!.length)
    }

    @Test
    fun `reads every calendar over the window, records failures, and caches what it read`() {
        val (good, bad) = existing(url, "https://broken.example.com/a.ics")
        whenever(fetcher.fetch(eq(URI(url)), any(), anyOrNull())).thenReturn(IcsFetcher.Result.Fetched(ics, IcsFetcher.Validators(null, null)))
        whenever(fetcher.fetch(eq(URI("https://broken.example.com/a.ics")), any(), anyOrNull())).thenThrow(IcsFetchException(IcsFetchError.NOT_FOUND))
        val from = Instant.parse("2026-11-26T00:00:00Z")
        val to = Instant.parse("2026-12-03T00:00:00Z")

        val readings = service.readWindow(userId, from, to, ZoneId.of("UTC"))

        assertEquals(listOf("Review"), readings[0].occurrences.map { it.summary })
        assertNull(readings[0].error)
        assertEquals(IcsFetchError.NOT_FOUND, readings[1].error)
        assertTrue(readings[1].occurrences.isEmpty())
        assertEquals("NOT_FOUND", bad.lastError)
        assertEquals(now, good.lastSuccessAt)

        // A second read within the cache's lifetime fetches only the calendar that failed.
        service.readWindow(userId, from, to, ZoneId.of("UTC"))
        verify(fetcher, times(1)).fetch(eq(URI(url)), any(), anyOrNull())
        verify(fetcher, times(2)).fetch(eq(URI("https://broken.example.com/a.ics")), any(), anyOrNull())
    }

    @Test
    fun `refresh ignores the cache`() {
        val (source) = existing(url)
        whenever(repository.findByIdAndUserId(source.id!!, userId)).thenReturn(source)
        fetchReturns()

        service.refresh(userId, source.id!!)
        service.refresh(userId, source.id!!)

        verify(fetcher, times(2)).fetch(any(), any(), anyOrNull())
    }

    @Test
    fun `another user's calendar is not found`() {
        whenever(repository.findByIdAndUserId(any(), eq(userId))).thenReturn(null)

        assertFailsWith<CalendarSourceNotFoundException> { service.delete(userId, UUID.randomUUID()) }
        assertFailsWith<CalendarSourceNotFoundException> { service.update(userId, UUID.randomUUID(), "x", null) }
    }

    @Test
    fun `reads nothing when the feature is off`() {
        val off = CalendarSourceService(
            repository, userCryptoService, fetcher, IcsParser(), CalendarSourceProperties(enabled = false), { true }, meterRegistry, clock,
        )
        try {
            assertTrue(off.readWindow(userId, now, now.plusSeconds(3600), ZoneId.of("UTC")).isEmpty())
            assertFailsWith<CalendarSourceNotFoundException> { off.add(userId, url, null, CalendarSourceDetail.TITLES) }
        } finally {
            off.destroy()
        }
    }
}
