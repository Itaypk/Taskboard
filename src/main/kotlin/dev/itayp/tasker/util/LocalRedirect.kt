package dev.itayp.tasker.util

/**
 * Defense-in-depth against open redirects: only ever redirect to a same-origin path.
 * Rejects absolute URLs, protocol-relative URLs (//), and paths with backslashes.
 */
fun localRedirect(path: String?): String =
    if (path != null && path.startsWith("/") && !path.startsWith("//") && !path.contains('\\'))
        path
    else "/"
