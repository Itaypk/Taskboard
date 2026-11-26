package dev.itayp.tasker.calendar.source

import dev.itayp.tasker.config.AppProperties
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.time.Duration

/**
 * Downloads iCalendar files over HTTPS: users' private calendar addresses (GitHub issue #347) and,
 * with [AddressPolicy.TRUSTED], URLs the app builds itself (#413).
 *
 * For [AddressPolicy.USER_SUPPLIED], the SSRF guard sits in OkHttp's [Dns] hook, which supplies
 * the addresses the connection is then opened to, so the address that was checked is the address
 * connected to. That covers DNS rebinding (a name resolving to a public address for a pre-check and
 * to a private one for the connection) and redirects into the internal network, since every hop
 * resolves through the same hook. OkHttp skips the hook for IP literals, so those are checked
 * before each hop. Redirects are followed by hand so each hop must stay on `https`.
 *
 * Bounded on every axis: connect and read timeouts, a per-hop call timeout that includes reading
 * the body, a size cap applied after decompression, no retries, no proxy (a proxy would resolve
 * names on our behalf and bypass the guard). Nothing here logs the URL or the body.
 */
@Component
class IcsFetcher(
    appProperties: AppProperties,
    private val limits: Limits,
    /** Decides whether a resolved address may be connected to for [AddressPolicy.USER_SUPPLIED]. */
    private val addressAllowed: (InetAddress) -> Boolean,
    /** Tests serve plain HTTP from a local server; production always requires HTTPS. */
    private val requireHttps: Boolean,
) {

    @Autowired
    constructor(properties: CalendarSourceProperties, appProperties: AppProperties) : this(
        appProperties, Limits(),
        addressAllowed = { properties.allowPrivateAddresses || PublicAddresses.isPublic(it) },
        requireHttps = true,
    )

    enum class AddressPolicy {
        /** A URL a user typed in: only public addresses. */
        USER_SUPPLIED,

        /** A URL the app built from its own configuration. */
        TRUSTED,
    }

    data class Limits(
        val maxBytes: Int = 10 * 1024 * 1024,
        val connectTimeout: Duration = Duration.ofSeconds(5),
        val readTimeout: Duration = Duration.ofSeconds(10),
        /** One request, from resolving the name to the last byte of the body. */
        val callTimeout: Duration = Duration.ofSeconds(15),
        val maxRedirects: Int = 3,
    )

    /** Validators from an earlier response, for a conditional request. */
    data class Validators(val etag: String?, val lastModified: String?)

    sealed interface Result {
        class Fetched(val body: ByteArray, val validators: Validators) : Result

        data object NotModified : Result
    }

    private val userAgent = "${appProperties.name} calendar reader (+${appProperties.baseUrl})"

    private val baseClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(limits.connectTimeout)
        .readTimeout(limits.readTimeout)
        .callTimeout(limits.callTimeout)
        .build()

    private val guardedClient = baseClient.newBuilder().dns(GuardedDns(addressAllowed)).build()

    /**
     * Fetches [uri], following up to [Limits.maxRedirects] redirects.
     *
     * @throws IcsFetchException for every failure, with a code that's safe to log and show.
     */
    fun fetch(uri: URI, policy: AddressPolicy, validators: Validators? = null): Result {
        val guarded = policy == AddressPolicy.USER_SUPPLIED
        val client = if (guarded) guardedClient else baseClient
        val first = checkScheme(uri.toString().toHttpUrlOrNull())
        var current = first
        repeat(limits.maxRedirects + 1) {
            if (guarded) checkLiteralAddress(current)
            val request = Request.Builder().url(current)
                .header("User-Agent", userAgent)
                .header("Accept", "text/calendar, */*;q=0.5")
                .apply {
                    // Conditional headers belong to the URL the caller asked for, not to a redirect target.
                    if (current == first) {
                        validators?.etag?.let { header("If-None-Match", it) }
                        validators?.lastModified?.let { header("If-Modified-Since", it) }
                    }
                }
                .build()
            val step = try {
                client.newCall(request).execute().use { handle(it, current) }
            } catch (e: IcsFetchException) {
                throw e
            } catch (e: IOException) {
                throw IcsFetchException(classify(e))
            }
            when (step) {
                is Step.Done -> return step.result
                is Step.Redirect -> current = step.location
            }
        }
        throw IcsFetchException(IcsFetchError.UNREACHABLE)
    }

    private sealed interface Step {
        class Done(val result: Result) : Step
        class Redirect(val location: HttpUrl) : Step
    }

    private fun handle(response: Response, current: HttpUrl): Step {
        val status = response.code
        return when {
            status == 304 -> Step.Done(Result.NotModified)
            status in REDIRECTS -> {
                val location = response.header("Location") ?: throw IcsFetchException(IcsFetchError.UNREACHABLE)
                Step.Redirect(checkScheme(current.resolve(location.trim())))
            }
            status == 404 || status == 410 -> throw IcsFetchException(IcsFetchError.NOT_FOUND)
            status == 401 || status == 403 -> throw IcsFetchException(IcsFetchError.UNAUTHORIZED)
            status !in 200..299 -> throw IcsFetchException(IcsFetchError.UNREACHABLE)
            else -> {
                val body = response.body ?: throw IcsFetchException(IcsFetchError.NOT_A_CALENDAR)
                if (body.contentLength() > limits.maxBytes) throw IcsFetchException(IcsFetchError.TOO_LARGE)
                val bytes = body.byteStream().use { readCapped(it) }
                if (!looksLikeICalendar(bytes)) throw IcsFetchException(IcsFetchError.NOT_A_CALENDAR)
                Step.Done(
                    Result.Fetched(
                        bytes,
                        Validators(etag = response.header("ETag"), lastModified = response.header("Last-Modified")),
                    ),
                )
            }
        }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limits.maxBytes) throw IcsFetchException(IcsFetchError.TOO_LARGE)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun checkScheme(url: HttpUrl?): HttpUrl {
        if (url == null) throw IcsFetchException(IcsFetchError.NOT_HTTPS)
        if (requireHttps && !url.isHttps) throw IcsFetchException(IcsFetchError.NOT_HTTPS)
        return url
    }

    /** OkHttp connects to IP-literal hosts without asking [Dns], so they're checked here. */
    private fun checkLiteralAddress(url: HttpUrl) {
        val host = url.host
        val literal = host.contains(':') || IPV4_LITERAL.matches(host)
        if (literal && !addressAllowed(InetAddress.getByName(host))) throw IcsFetchException(IcsFetchError.BLOCKED_ADDRESS)
    }

    private fun classify(e: IOException): IcsFetchError {
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is BlockedAddressException -> return IcsFetchError.BLOCKED_ADDRESS
                // OkHttp's timeouts: SocketTimeoutException (connect/read) and a plain
                // InterruptedIOException("timeout") for the call timeout.
                is InterruptedIOException -> return IcsFetchError.TIMEOUT
            }
            cause = cause.cause
        }
        return IcsFetchError.UNREACHABLE
    }

    /** Refuses names that resolve to any address [allowed] rejects (all of them, not just the first). */
    private class GuardedDns(private val allowed: (InetAddress) -> Boolean) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty() || !addresses.all(allowed)) throw BlockedAddressException()
            return addresses
        }
    }

    /** An [UnknownHostException], so OkHttp treats it as a resolution failure and gives up. */
    private class BlockedAddressException : UnknownHostException("blocked address")

    companion object {
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        /** True when [body] starts (after a BOM and blank lines) with `BEGIN:VCALENDAR`. */
        fun looksLikeICalendar(body: ByteArray): Boolean {
            var i = 0
            if (body.size >= 3 && body[0] == 0xEF.toByte() && body[1] == 0xBB.toByte() && body[2] == 0xBF.toByte()) i = 3
            while (i < body.size && body[i].toInt().toChar() in " \t\r\n") i++
            val marker = "BEGIN:VCALENDAR"
            if (body.size - i < marker.length) return false
            return marker.indices.all { k -> body[i + k].toInt().toChar().uppercaseChar() == marker[k] }
        }
    }
}
