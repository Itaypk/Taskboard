package dev.itayp.tasker.ratelimit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryRateLimiterTest {

    @Test
    fun `allows requests up to the limit`() {
        val limiter = InMemoryRateLimiter(limit = 3, windowMillis = 60_000)
        assertThat(limiter.tryConsume("user-a")).isTrue()
        assertThat(limiter.tryConsume("user-a")).isTrue()
        assertThat(limiter.tryConsume("user-a")).isTrue()
    }

    @Test
    fun `rejects the request that exceeds the limit`() {
        val limiter = InMemoryRateLimiter(limit = 3, windowMillis = 60_000)
        repeat(3) { limiter.tryConsume("user-a") }
        assertThat(limiter.tryConsume("user-a")).isFalse()
    }

    @Test
    fun `keys are independent`() {
        val limiter = InMemoryRateLimiter(limit = 1, windowMillis = 60_000)
        assertThat(limiter.tryConsume("user-a")).isTrue()
        assertThat(limiter.tryConsume("user-b")).isTrue()
        assertThat(limiter.tryConsume("user-a")).isFalse()
    }

    @Test
    fun `allows requests again after the window has passed`() {
        val limiter = InMemoryRateLimiter(limit = 1, windowMillis = 50)
        assertThat(limiter.tryConsume("user-a")).isTrue()
        assertThat(limiter.tryConsume("user-a")).isFalse()

        Thread.sleep(60)

        assertThat(limiter.tryConsume("user-a")).isTrue()
    }
}
