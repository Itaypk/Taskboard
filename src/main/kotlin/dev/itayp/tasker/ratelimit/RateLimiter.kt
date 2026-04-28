package dev.itayp.tasker.ratelimit

fun interface RateLimiter {
    /** Returns true if the request is allowed, false if the limit is exceeded. */
    fun tryConsume(key: String): Boolean
}
