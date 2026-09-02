package dev.itayp.tasker.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class LocaleNegotiationServiceTest {

    private val service = LocaleNegotiationService()

    @Test
    fun `matches a browser header against the supported list`() {
        assertThat(service.resolveSupportedTag("he-IL,he;q=0.9,en;q=0.8")).isEqualTo("he")
        assertThat(service.resolveSupportedTag("ru")).isEqualTo("ru")
        // Bare `en` resolves to US English, the historical default, not en-GB.
        assertThat(service.resolveSupportedTag("en")).isEqualTo("en-US")
    }

    @Test
    fun `returns null for blank, malformed or unsupported headers`() {
        assertThat(service.resolveSupportedTag(null)).isNull()
        assertThat(service.resolveSupportedTag("   ")).isNull()
        assertThat(service.resolveSupportedTag("de-DE,de;q=0.9")).isNull()
        assertThat(service.resolveSupportedTag(";;;not a language;;;")).isNull()
    }

    @Test
    fun `an explicit choice outranks the browser header`() {
        // The case the anonymous language switcher exists for: reading the site in Hebrew on an
        // en-US browser must seed a Hebrew account, not an English one.
        assertThat(service.resolveSupportedTag("he", "en-US,en;q=0.9")).isEqualTo("he")
    }

    @Test
    fun `falls back to the header when the explicit choice is absent or unusable`() {
        assertThat(service.resolveSupportedTag(null, "he-IL")).isEqualTo("he")
        assertThat(service.resolveSupportedTag("", "he-IL")).isEqualTo("he")
        // Unsupported and outright junk both fall through rather than winning or failing the request.
        assertThat(service.resolveSupportedTag("de", "he-IL")).isEqualTo("he")
        assertThat(service.resolveSupportedTag("../../etc/passwd", "he-IL")).isEqualTo("he")
    }

    @Test
    fun `an over-long explicit tag cannot reach the parser intact`() {
        // `lang` is untrusted query input; the cap bounds what LanguageRange.parse ever sees.
        // "he" + ",en" x 11 is exactly 35 chars, so this truncates on a clean subtag boundary and
        // the leading choice still wins — the cap trims the tail, it doesn't corrupt the head.
        val flood = "he" + ",en".repeat(10_000)
        assertThat(service.resolveSupportedTag(flood, "ru")).isEqualTo("he")

        // Nothing recognisable survives the cap here, so it falls through to the header.
        assertThat(service.resolveSupportedTag("x".repeat(10_000), "ru")).isEqualTo("ru")
    }

    @Test
    fun `resolveLocale always yields a locale, defaulting to English`() {
        assertThat(service.resolveLocale("he-IL")).isEqualTo(Locale.forLanguageTag("he"))
        assertThat(service.resolveLocale("de-DE")).isEqualTo(Locale.ENGLISH)
        assertThat(service.resolveLocale(null)).isEqualTo(Locale.ENGLISH)
    }
}
