package dev.itayp.tasker.crypto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

class InboundMailTokenHasherTest {

    private fun hasher(kekByte: Int) =
        InboundMailTokenHasher(DataEncryptionProperties(Base64.getEncoder().encodeToString(ByteArray(32) { kekByte.toByte() })))

    @Test
    fun `is deterministic, 64 hex chars, and ignores case`() {
        val hasher = hasher(1)

        val hash = hasher.hash("k7f3x5q2mz")

        assertThat(hash).matches("[0-9a-f]{64}")
        assertThat(hasher.hash("k7f3x5q2mz")).isEqualTo(hash)
        assertThat(hasher.hash("K7F3X5Q2MZ")).isEqualTo(hash)
        assertThat(hasher.hash("k7f3x5q2ma")).isNotEqualTo(hash)
    }

    @Test
    fun `is keyed by the KEK, so a dump alone can't be brute-forced with plain SHA-256`() {
        val sha256 = MessageDigest.getInstance("SHA-256").digest("k7f3x5q2mz".toByteArray())
            .joinToString("") { "%02x".format(it) }

        assertThat(hasher(1).hash("k7f3x5q2mz")).isNotEqualTo(sha256)
        assertThat(hasher(1).hash("k7f3x5q2mz")).isNotEqualTo(hasher(2).hash("k7f3x5q2mz"))
    }
}
