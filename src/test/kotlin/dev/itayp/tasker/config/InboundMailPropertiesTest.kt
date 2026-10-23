package dev.itayp.tasker.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InboundMailPropertiesTest {

    @Test
    fun `is active only when enabled with a domain and a long enough delivery token`() {
        val token = "x".repeat(32)
        assertThat(InboundMailProperties(enabled = true, domain = "m.example.com", deliveryToken = token).active).isTrue()
        assertThat(InboundMailProperties(enabled = true, domain = "", deliveryToken = token).active).isFalse()
        assertThat(InboundMailProperties(enabled = false, domain = "m.example.com", deliveryToken = token).active).isFalse()
        assertThat(InboundMailProperties(enabled = true, domain = "m.example.com", deliveryToken = "short").active).isFalse()
    }

    @Test
    fun `defaults the authserv-id to the domain, and never prints the token`() {
        val properties = InboundMailProperties(enabled = true, domain = "M.Example.com", deliveryToken = "s3cret".repeat(6))

        assertThat(properties.effectiveAuthservId).isEqualTo("m.example.com")
        assertThat(properties.toString()).doesNotContain("s3cret")
    }

    @Test
    fun `normalizes the domain`() {
        assertThat(InboundMailProperties(enabled = true, domain = " M.Example.COM ").normalizedDomain)
            .isEqualTo("m.example.com")
    }

    @Test
    fun `rejects something that isn't a domain name`() {
        assertThatThrownBy { InboundMailProperties(enabled = true, domain = "tasks@m.example.com") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { InboundMailProperties(enabled = true, domain = "localhost") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
