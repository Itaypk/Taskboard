package dev.itayp.tasker.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InboundMailPropertiesTest {

    @Test
    fun `is active only when enabled with a domain`() {
        assertThat(InboundMailProperties(enabled = true, domain = "m.example.com").active).isTrue()
        assertThat(InboundMailProperties(enabled = true, domain = "").active).isFalse()
        assertThat(InboundMailProperties(enabled = false, domain = "m.example.com").active).isFalse()
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
