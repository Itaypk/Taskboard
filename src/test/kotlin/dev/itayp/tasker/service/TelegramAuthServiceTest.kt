package dev.itayp.tasker.service

import dev.itayp.tasker.config.TelegramAuthProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TelegramAuthServiceTest {

    private val botToken = "12345:TEST-TOKEN"
    private val fixedNow = Instant.parse("2026-04-21T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val service = TelegramAuthService(
        TelegramAuthProperties(botToken = botToken, botUsername = "tester_bot"),
        clock,
    )

    private fun payloadFor(
        fields: Map<String, String>,
        overrideHash: String? = null,
    ): Map<String, String> {
        val dataCheckString = fields.asSequence()
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value}" }
        val secretKey = MessageDigest.getInstance("SHA-256")
            .digest(botToken.toByteArray(StandardCharsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secretKey, "HmacSHA256"))
        }
        val hash = mac.doFinal(dataCheckString.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return fields + ("hash" to (overrideHash ?: hash))
    }

    private fun baseFields(authDate: Long = fixedNow.epochSecond) = mapOf(
        "id" to "42",
        "first_name" to "Alice",
        "username" to "alice",
        "photo_url" to "https://t.me/a.png",
        "auth_date" to authDate.toString(),
    )

    @Test
    fun `verify accepts a valid payload and parses fields`() {
        val data = service.verify(payloadFor(baseFields()))

        assertThat(data.telegramId).isEqualTo(42L)
        assertThat(data.username).isEqualTo("alice")
        assertThat(data.firstName).isEqualTo("Alice")
        assertThat(data.photoUrl).isEqualTo("https://t.me/a.png")
        assertThat(data.authDate).isEqualTo(fixedNow)
    }

    @Test
    fun `verify accepts forward-compatible unknown fields`() {
        val fields = baseFields() + ("last_name" to "Smith") + ("future_field" to "x")
        val data = service.verify(payloadFor(fields))
        assertThat(data.telegramId).isEqualTo(42L)
    }

    @Test
    fun `verify rejects a tampered hash`() {
        val payload = payloadFor(baseFields())
        val tampered = payload.toMutableMap().apply {
            val orig = getValue("hash")
            val flippedFirstChar = if (orig[0] == '0') '1' else '0'
            this["hash"] = flippedFirstChar + orig.substring(1)
        }
        assertThatThrownBy { service.verify(tampered) }
            .isInstanceOf(TelegramAuthException::class.java)
    }

    @Test
    fun `verify rejects a missing hash`() {
        assertThatThrownBy { service.verify(baseFields()) }
            .isInstanceOf(TelegramAuthException::class.java)
            .hasMessageContaining("hash")
    }

    @Test
    fun `verify rejects a missing id`() {
        val fields = baseFields().filterKeys { it != "id" }
        assertThatThrownBy { service.verify(payloadFor(fields)) }
            .isInstanceOf(TelegramAuthException::class.java)
    }

    @Test
    fun `verify rejects stale auth_date`() {
        val stale = fixedNow.epochSecond - 120
        assertThatThrownBy { service.verify(payloadFor(baseFields(authDate = stale))) }
            .isInstanceOf(TelegramAuthException::class.java)
            .hasMessageContaining("Stale")
    }

    @Test
    fun `verify rejects auth_date too far in the future`() {
        val future = fixedNow.epochSecond + 120
        assertThatThrownBy { service.verify(payloadFor(baseFields(authDate = future))) }
            .isInstanceOf(TelegramAuthException::class.java)
    }

    @Test
    fun `verify accepts small clock skew within tolerance`() {
        val slightFuture = fixedNow.epochSecond + 10
        val data = service.verify(payloadFor(baseFields(authDate = slightFuture)))
        assertThat(data.telegramId).isEqualTo(42L)
    }

    @Test
    fun `verify fails fast when bot token is not configured`() {
        val noToken = TelegramAuthService(
            TelegramAuthProperties(botToken = "", botUsername = ""),
            clock,
        )
        assertThatThrownBy { noToken.verify(payloadFor(baseFields())) }
            .isInstanceOf(TelegramAuthException::class.java)
            .hasMessageContaining("not configured")
    }
}
