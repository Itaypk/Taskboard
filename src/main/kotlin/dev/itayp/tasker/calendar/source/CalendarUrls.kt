package dev.itayp.tasker.calendar.source

import java.net.URI
import java.net.URISyntaxException

/** Turns what a user pasted into the URL we fetch. */
object CalendarUrls {

    private const val MAX_LENGTH = 2048

    /**
     * Trims, maps `webcal://` (how calendar apps advertise subscriptions) to `https://`, and
     * refuses anything else that isn't `https://`: plain `http://` would send the secret in the
     * clear. Credentials in the URL are refused too, since they'd be logged by proxies upstream.
     */
    fun normalize(raw: String): URI {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) throw IcsFetchException(IcsFetchError.NOT_HTTPS)
        val withScheme = when {
            trimmed.startsWith("webcals://", ignoreCase = true) -> "https://" + trimmed.substring("webcals://".length)
            trimmed.startsWith("webcal://", ignoreCase = true) -> "https://" + trimmed.substring("webcal://".length)
            else -> trimmed
        }
        val uri = try {
            URI(withScheme)
        } catch (_: URISyntaxException) {
            throw IcsFetchException(IcsFetchError.NOT_HTTPS)
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
            throw IcsFetchException(IcsFetchError.NOT_HTTPS)
        }
        return uri
    }
}
