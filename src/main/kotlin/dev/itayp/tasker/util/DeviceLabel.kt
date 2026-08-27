package dev.itayp.tasker.util

/**
 * Best-effort human label for a User-Agent, e.g. "Chrome on macOS", shown in the active-sessions
 * list so a user can recognise their own devices. Deliberately coarse and dependency-free: the
 * only job is "is this me?", not analytics, so an unrecognised agent degrades to a browser name,
 * an OS name, or null rather than exposing the raw string.
 *
 * The raw User-Agent is never stored or logged — only this label.
 */
fun deviceLabel(userAgent: String?): String? {
    val ua = userAgent?.take(MAX_UA_LENGTH)?.takeIf { it.isNotBlank() } ?: return null
    val browser = BROWSERS.firstOrNull { (_, marker) -> marker(ua) }?.first
    val os = OSES.firstOrNull { (_, marker) -> ua.contains(marker, ignoreCase = true) }?.first
    return when {
        browser != null && os != null -> "$browser on $os"
        else -> browser ?: os
    }
}

private const val MAX_UA_LENGTH = 400

// Order matters: every Chromium browser also says "Chrome", and Chrome/Edge both say "Safari",
// so the more specific brands have to be tested first.
private val BROWSERS: List<Pair<String, (String) -> Boolean>> = listOf(
    "Edge" to { ua -> ua.contains("Edg/") || ua.contains("Edge/") },
    "Opera" to { ua -> ua.contains("OPR/") || ua.contains("Opera") },
    "Samsung Internet" to { ua -> ua.contains("SamsungBrowser") },
    "Firefox" to { ua -> ua.contains("Firefox/") || ua.contains("FxiOS") },
    "Chrome" to { ua -> ua.contains("Chrome/") || ua.contains("CriOS") },
    "Safari" to { ua -> ua.contains("Safari/") },
)

// "iPhone"/"iPad" before "Mac": iOS agents mention Mac OS X too. "Android" before "Linux"
// for the same reason.
private val OSES: List<Pair<String, String>> = listOf(
    "iPhone" to "iPhone",
    "iPad" to "iPad",
    "Android" to "Android",
    "Windows" to "Windows",
    "macOS" to "Mac OS X",
    "Linux" to "Linux",
)
