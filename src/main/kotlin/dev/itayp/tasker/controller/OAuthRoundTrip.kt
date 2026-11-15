package dev.itayp.tasker.controller

import dev.itayp.tasker.util.localRedirect
import jakarta.servlet.http.HttpSession
import org.springframework.http.ResponseEntity
import java.net.URI

/**
 * The session half of an OAuth Authorization Code + PKCE round-trip, shared by the Telegram and
 * Google login controllers. `/start` stashes the state, PKCE verifier and what to do on return in
 * the HTTP session; the provider's callback (a top-level redirect, so it can't carry the SPA's XSRF
 * header) reads them back exactly once. The stored `state` is the callback's anti-forgery token.
 *
 * Each provider gets its own attribute [prefix], so a login started with one provider can't be
 * completed by the other's callback.
 */
class OAuthRoundTrip(private val prefix: String) {

    /** Starts a round-trip. [next], [language] and [timeZone] only matter for a login (registration hints). */
    fun begin(
        session: HttpSession,
        state: String,
        codeVerifier: String,
        mode: OAuthMode,
        next: String? = null,
        language: String? = null,
        timeZone: String? = null,
    ) {
        session.setAttribute(attr(STATE), state)
        session.setAttribute(attr(VERIFIER), codeVerifier)
        session.setAttribute(attr(MODE), mode.value)
        next?.let { session.setAttribute(attr(NEXT), it) }
        language?.let { session.setAttribute(attr(LANGUAGE), it) }
        timeZone?.let { session.setAttribute(attr(TIME_ZONE), it) }
    }

    /**
     * Reads and clears the stored round-trip. With no stored mode, this browser never ran a `/start`
     * (or already used the callback once); a signed-in user can then only have been linking, because
     * the SPA offers login only to signed-out visitors.
     */
    fun consume(session: HttpSession, signedIn: Boolean): PendingOAuth {
        val mode = OAuthMode.of(session.getAttribute(attr(MODE)) as? String)
            ?: if (signedIn) OAuthMode.LINK else OAuthMode.LOGIN
        val pending = PendingOAuth(
            mode = mode,
            state = session.getAttribute(attr(STATE)) as? String,
            codeVerifier = session.getAttribute(attr(VERIFIER)) as? String,
            next = session.getAttribute(attr(NEXT)) as? String ?: "/",
            language = session.getAttribute(attr(LANGUAGE)) as? String,
            timeZone = session.getAttribute(attr(TIME_ZONE)) as? String,
        )
        listOf(STATE, VERIFIER, MODE, NEXT, LANGUAGE, TIME_ZONE).forEach { session.removeAttribute(attr(it)) }
        return pending
    }

    private fun attr(suffix: String) = prefix + suffix

    companion object {
        private const val STATE = "State"
        private const val VERIFIER = "Verifier"
        private const val MODE = "Mode"
        private const val NEXT = "Next"
        private const val LANGUAGE = "Language"
        private const val TIME_ZONE = "TimeZone"

        /** Authorization URLs are absolute (the provider); SPA notices are same-origin paths run through [localRedirect]. */
        fun redirect(target: String): ResponseEntity<Void> {
            val location = if (target.startsWith("http")) target else localRedirect(target)
            return ResponseEntity.status(302).location(URI.create(location)).build()
        }
    }
}

enum class OAuthMode(val value: String) {
    LOGIN("login"),
    LINK("link"),
    ;

    companion object {
        fun of(value: String?): OAuthMode? = entries.firstOrNull { it.value == value }
    }
}

data class PendingOAuth(
    val mode: OAuthMode,
    val state: String?,
    val codeVerifier: String?,
    val next: String,
    val language: String?,
    val timeZone: String?,
) {
    /**
     * Why the callback can't proceed, or null when it can. A missing verifier with code and state
     * present means the session that ran `/start` isn't the one that came back (the provider reopened
     * the callback in another browser or cookie jar).
     */
    fun rejection(code: String?, state: String?, error: String?): String? {
        if (!error.isNullOrBlank()) return "error=$error"
        val missing = listOfNotNull(
            "code".takeIf { code.isNullOrBlank() },
            "state".takeIf { state.isNullOrBlank() },
            "verifier".takeIf { codeVerifier == null },
        )
        if (missing.isNotEmpty()) return "missing=$missing"
        if (this.state == null || state != this.state) return "state mismatch"
        return null
    }
}
