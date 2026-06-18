package dev.itayp.tasker.util

import jakarta.servlet.http.HttpServletRequest

/**
 * Nginx in front of this app sets `X-Forwarded-For: $proxy_add_x_forwarded_for`, which
 * APPENDS the immediate client IP to whatever XFF the client supplied. Taking the *last*
 * entry therefore yields the address Nginx saw — which is what we want for per-IP
 * throttling and logging. Taking the first entry (a previous, common mistake here) would
 * trust the attacker-supplied value and make IP-based controls trivially bypassable.
 */
fun HttpServletRequest.clientIp(): String =
    getHeader("X-Forwarded-For")
        ?.split(',')
        ?.lastOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: remoteAddr