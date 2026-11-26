package dev.itayp.tasker.calendar.source

/**
 * Why a calendar couldn't be read. The codes are shown to the user (Settings) and to the planner,
 * and are the only thing ever logged about a failed fetch: exception messages can carry the URL,
 * and for a user's calendar the URL is the secret.
 */
enum class IcsFetchError {
    /** Not `https://` (or `webcal://`), or not a URL at all. */
    NOT_HTTPS,

    /** The host resolves to an address the server mustn't reach (loopback, private, link-local, …). */
    BLOCKED_ADDRESS,

    /** 404 or 410: usually the provider's secret address was reset. */
    NOT_FOUND,

    /** 401 or 403. */
    UNAUTHORIZED,

    /** The response isn't an iCalendar file (typically a calendar's web page instead of its ICS link). */
    NOT_A_CALENDAR,

    /** Over the size cap. */
    TOO_LARGE,

    TIMEOUT,

    /** DNS failure, refused connection, TLS failure, too many redirects, or another HTTP status. */
    UNREACHABLE,

    /** It looked like iCalendar but couldn't be parsed, or is unreasonably complex. */
    PARSE_ERROR,
}

class IcsFetchException(val error: IcsFetchError) : RuntimeException(error.name) {
    // A code is all callers need; no stack trace for an expected outcome.
    override fun fillInStackTrace(): Throwable = this
}
