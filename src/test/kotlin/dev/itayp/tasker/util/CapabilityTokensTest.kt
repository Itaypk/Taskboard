package dev.itayp.tasker.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CapabilityTokensTest {

    @Test
    fun `hash is the lowercase hex SHA-256 digest`() {
        // FIPS 180-2 test vector for "abc".
        assertThat(CapabilityTokens.hash("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `hash fits the 64-char token columns`() {
        assertThat(CapabilityTokens.hash(CapabilityTokens.generate())).hasSize(64)
    }

    @Test
    fun `generate returns distinct URL-safe secrets`() {
        val tokens = List(50) { CapabilityTokens.generate() }

        assertThat(tokens).doesNotHaveDuplicates()
        assertThat(tokens).allMatch { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
    }
}
