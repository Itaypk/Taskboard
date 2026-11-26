package dev.itayp.tasker.calendar.source

import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.CalendarSourceEntity
import dev.itayp.tasker.ratelimit.RateLimiter
import dev.itayp.tasker.repository.CalendarSourceRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/** A connected calendar as Settings shows it. Never the address: only its host. */
data class CalendarSourceView(
    val id: UUID,
    val label: String,
    val host: String,
    val detail: CalendarSourceDetail,
    val createdAt: Instant,
    val lastAttemptAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastError: IcsFetchError?,
    val lastEventCount: Int?,
)

/** One calendar's events over a window, or why it couldn't be read. */
data class CalendarSourceReading(
    val sourceId: UUID,
    val label: String,
    val detail: CalendarSourceDetail,
    val occurrences: List<CalendarOccurrence>,
    val error: IcsFetchError?,
)

/** A refused add or refresh. [code] is an [IcsFetchError] name or one of the companion's codes. */
class CalendarSourceException(val code: String, val status: HttpStatus = HttpStatus.BAD_REQUEST) :
    RuntimeException(code) {
    companion object {
        const val LIMIT_REACHED = "LIMIT_REACHED"
        const val DUPLICATE = "DUPLICATE"
        const val RATE_LIMITED = "RATE_LIMITED"
    }
}

@ResponseStatus(HttpStatus.NOT_FOUND)
class CalendarSourceNotFoundException : RuntimeException("No such calendar")

/**
 * The calendars the planner reads (GitHub issue #347): a user's private ICS addresses.
 *
 * The address is a capability (it reads the whole calendar), so it is stored encrypted under the
 * user's DEK and never returned once added; Settings sees the host only. Nothing from the calendar
 * is stored. Calendars are fetched when a planning conversation is assembled, in parallel and
 * under one deadline, and the parsed result is kept in memory for [CACHE_TTL] so a revision soon
 * after doesn't fetch again. Each fetch records its outcome on the row for Settings.
 *
 * Adding a calendar reads it once, and refuses to save an address that doesn't work; adding and
 * "check again" share a per-user rate limit, so the endpoint can't be used as a fetch proxy.
 *
 * Never log an address, a label or anything read from a calendar: ids and error codes only.
 */
@Service
class CalendarSourceService(
    private val repository: CalendarSourceRepository,
    private val userCryptoService: UserCryptoService,
    private val fetcher: IcsFetcher,
    private val parser: IcsParser,
    private val properties: CalendarSourceProperties,
    @Qualifier("calendarSourceRateLimiter") private val rateLimiter: RateLimiter,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) : DisposableBean {
    private val log = LoggerFactory.getLogger(CalendarSourceService::class.java)

    private val cache = TtlCache<UUID, ParsedCalendar>(CACHE_TTL, MAX_CACHED, clock)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val fetchTimer = Timer.builder("tasker.calendar_source.fetch.duration")
        .description("Time to fetch and parse one of a user's calendars")
        .register(meterRegistry)

    val enabled: Boolean get() = properties.enabled

    fun list(userId: UUID): List<CalendarSourceView> =
        repository.findAllByUserIdOrderByCreatedAtAsc(userId).map { it.toView(userId) }

    /**
     * Adds a calendar after reading it once.
     *
     * @throws CalendarSourceException when the address doesn't work (an [IcsFetchError] code), the
     *   user already has [MAX_SOURCES] calendars or this one, or adds too fast.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun add(userId: UUID, rawUrl: String, label: String?, detail: CalendarSourceDetail): CalendarSourceView {
        requireEnabled()
        val uri = try {
            CalendarUrls.normalize(rawUrl)
        } catch (e: IcsFetchException) {
            throw CalendarSourceException(e.error.name)
        }
        val existing = repository.findAllByUserIdOrderByCreatedAtAsc(userId)
        if (existing.size >= MAX_SOURCES) throw CalendarSourceException(CalendarSourceException.LIMIT_REACHED)
        if (existing.any { userCryptoService.decrypt(userId, it.url) == uri.toString() }) {
            throw CalendarSourceException(CalendarSourceException.DUPLICATE)
        }
        consumeRateLimit(userId)

        val parsed = try {
            fetchAndParse(uri.toString())
        } catch (e: IcsFetchException) {
            log.info("Refused to add a calendar for user {}: {}", userId, e.error)
            throw CalendarSourceException(e.error.name)
        }
        val now = clock.instant()
        val entity = CalendarSourceEntity().apply {
            id = Uuid.generateV7().toJavaUuid()
            this.userId = userId
            url = userCryptoService.encrypt(userId, uri.toString())
            this.label = userCryptoService.encrypt(userId, cleanLabel(label) ?: uri.host)
            this.detail = detail.name
            createdAt = now
            lastAttemptAt = now
            lastSuccessAt = now
            lastEventCount = countUpcoming(parsed, now)
        }
        repository.save(entity)
        cache.put(entity.id!!, parsed)
        log.info("Added calendar {} for user {}", entity.id, userId)
        return entity.toView(userId)
    }

    fun update(userId: UUID, id: UUID, label: String?, detail: CalendarSourceDetail?): CalendarSourceView {
        val entity = repository.findByIdAndUserId(id, userId) ?: throw CalendarSourceNotFoundException()
        cleanLabel(label)?.let { entity.label = userCryptoService.encrypt(userId, it) }
        detail?.let { entity.detail = it.name }
        repository.save(entity)
        log.debug("Updated calendar {} for user {}", id, userId)
        return entity.toView(userId)
    }

    fun delete(userId: UUID, id: UUID) {
        val entity = repository.findByIdAndUserId(id, userId) ?: throw CalendarSourceNotFoundException()
        repository.delete(entity)
        cache.invalidate(id)
        log.info("Removed calendar {} for user {}", id, userId)
    }

    /** "Check again": reads the calendar now, ignoring the cache, and returns its new status. */
    fun refresh(userId: UUID, id: UUID): CalendarSourceView {
        requireEnabled()
        val entity = repository.findByIdAndUserId(id, userId) ?: throw CalendarSourceNotFoundException()
        consumeRateLimit(userId)
        cache.invalidate(id)
        val url = checkNotNull(userCryptoService.decrypt(userId, entity.url))
        val outcome = try {
            Result.success(fetchAndParse(url))
        } catch (e: IcsFetchException) {
            Result.failure(e)
        }
        outcome.getOrNull()?.let { cache.put(id, it) }
        recordOutcome(entity, outcome)
        return entity.toView(userId)
    }

    /**
     * Every calendar of [userId] over [from, to), read in parallel under [READ_DEADLINE]. A
     * calendar that fails or runs out of time comes back with its error and no events, and never
     * holds up the others. Empty when the feature is off.
     */
    fun readWindow(userId: UUID, from: Instant, to: Instant, zone: ZoneId): List<CalendarSourceReading> {
        if (!properties.enabled) return emptyList()
        val sources = repository.findAllByUserIdOrderByCreatedAtAsc(userId)
        if (sources.isEmpty()) return emptyList()

        val pending: List<Pair<CalendarSourceEntity, Future<Result<Pair<ParsedCalendar, Boolean>>>>> = sources.map { source ->
            val id = checkNotNull(source.id)
            val url = checkNotNull(userCryptoService.decrypt(userId, source.url))
            source to executor.submit<Result<Pair<ParsedCalendar, Boolean>>> {
                cache.get(id)?.let { return@submit Result.success(it to false) }
                try {
                    Result.success(fetchAndParse(url).also { cache.put(id, it) } to true)
                } catch (e: IcsFetchException) {
                    Result.failure(e)
                }
            }
        }

        val deadline = clock.instant().plus(READ_DEADLINE)
        return pending.map { (source, future) ->
            val result = awaitUntil(future, deadline)
            val fetchedNow = result.getOrNull()?.second ?: true
            if (fetchedNow) recordOutcome(source, result.map { it.first })
            val label = userCryptoService.decrypt(userId, source.label) ?: ""
            val detail = detailOf(source)
            result.fold(
                onSuccess = { (parsed, _) ->
                    CalendarSourceReading(source.id!!, label, detail, parsed.occurrences(from, to, zone), null)
                },
                onFailure = { e ->
                    CalendarSourceReading(source.id!!, label, detail, emptyList(), (e as? IcsFetchException)?.error ?: IcsFetchError.UNREACHABLE)
                },
            )
        }
    }

    private fun <T> awaitUntil(future: Future<Result<T>>, deadline: Instant): Result<T> {
        val remaining = Duration.between(clock.instant(), deadline).toMillis().coerceAtLeast(0)
        return try {
            future.get(remaining, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            Result.failure(IcsFetchException(IcsFetchError.TIMEOUT))
        } catch (_: ExecutionException) {
            Result.failure(IcsFetchException(IcsFetchError.UNREACHABLE))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.failure(IcsFetchException(IcsFetchError.TIMEOUT))
        }
    }

    private fun fetchAndParse(url: String): ParsedCalendar {
        val sample = Timer.start(meterRegistry)
        var outcome = "ok"
        try {
            val result = fetcher.fetch(java.net.URI(url), IcsFetcher.AddressPolicy.USER_SUPPLIED)
            val body = (result as? IcsFetcher.Result.Fetched)?.body
                ?: throw IcsFetchException(IcsFetchError.UNREACHABLE) // no validators sent, so no 304 expected
            return parser.parse(body)
        } catch (e: IcsFetchException) {
            outcome = e.error.name.lowercase()
            throw e
        } finally {
            sample.stop(fetchTimer)
            meterRegistry.counter(FETCHES_METRIC, "outcome", outcome).increment()
        }
    }

    private fun recordOutcome(entity: CalendarSourceEntity, outcome: Result<ParsedCalendar>) {
        val now = clock.instant()
        entity.lastAttemptAt = now
        outcome.fold(
            onSuccess = { parsed ->
                entity.lastSuccessAt = now
                entity.lastError = null
                entity.lastEventCount = countUpcoming(parsed, now)
            },
            onFailure = { e ->
                val error = (e as? IcsFetchException)?.error ?: IcsFetchError.UNREACHABLE
                entity.lastError = error.name
                log.info("Couldn't read calendar {} for user {}: {}", entity.id, entity.userId, error)
            },
        )
        // The row may have been removed while it was being read; that's fine, there's nothing to record.
        if (repository.existsById(checkNotNull(entity.id))) repository.save(entity)
    }

    private fun countUpcoming(parsed: ParsedCalendar, now: Instant): Int =
        parsed.occurrences(now, now.plus(Duration.ofDays(7)), ZoneId.of("UTC")).size

    private fun consumeRateLimit(userId: UUID) {
        if (!rateLimiter.tryConsume(userId.toString())) {
            throw CalendarSourceException(CalendarSourceException.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS)
        }
    }

    private fun requireEnabled() {
        if (!properties.enabled) throw CalendarSourceNotFoundException()
    }

    private fun detailOf(entity: CalendarSourceEntity) =
        runCatching { CalendarSourceDetail.valueOf(entity.detail!!) }.getOrDefault(CalendarSourceDetail.TITLES)

    private fun CalendarSourceEntity.toView(userId: UUID): CalendarSourceView {
        val plainUrl = userCryptoService.decrypt(userId, url)
        return CalendarSourceView(
            id = checkNotNull(id),
            label = userCryptoService.decrypt(userId, label) ?: "",
            host = plainUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: "",
            detail = detailOf(this),
            createdAt = checkNotNull(createdAt),
            lastAttemptAt = lastAttemptAt,
            lastSuccessAt = lastSuccessAt,
            lastError = lastError?.let { runCatching { IcsFetchError.valueOf(it) }.getOrNull() },
            lastEventCount = lastEventCount,
        )
    }

    override fun destroy() {
        executor.shutdownNow()
    }

    companion object {
        const val MAX_SOURCES = 5
        const val MAX_LABEL_LENGTH = 40
        const val FETCHES_METRIC = "tasker.calendar_source.fetches"
        val CACHE_TTL: Duration = Duration.ofMinutes(15)
        val READ_DEADLINE: Duration = Duration.ofSeconds(20)
        private const val MAX_CACHED = 500

        fun cleanLabel(label: String?): String? =
            label?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_LABEL_LENGTH)?.takeIf { it.isNotEmpty() }
    }
}
