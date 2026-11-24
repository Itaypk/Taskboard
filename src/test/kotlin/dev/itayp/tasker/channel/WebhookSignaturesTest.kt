package dev.itayp.tasker.channel

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WebhookSignaturesTest {

    @Test
    fun `computes HMAC-SHA256 as lowercase hex`() {
        // The well-known Wikipedia HMAC example vector.
        val hex = WebhookSignatures.hmacHex("HmacSHA256", "key", "The quick brown fox jumps over the lazy dog".toByteArray())

        assertThat(hex).isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")
    }

    @Test
    fun `matches regardless of the provided hex case, and never a missing or different value`() {
        val expected = WebhookSignatures.hmacHex("HmacSHA256", "key", "body".toByteArray())

        assertThat(WebhookSignatures.matches(expected, expected)).isTrue()
        assertThat(WebhookSignatures.matches(expected, expected.uppercase())).isTrue()
        assertThat(WebhookSignatures.matches(expected, null)).isFalse()
        assertThat(WebhookSignatures.matches(expected, "")).isFalse()
        assertThat(WebhookSignatures.matches(expected, WebhookSignatures.hmacHex("HmacSHA256", "other", "body".toByteArray()))).isFalse()
    }
}
