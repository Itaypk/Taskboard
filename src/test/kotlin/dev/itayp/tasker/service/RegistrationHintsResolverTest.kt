package dev.itayp.tasker.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RegistrationHintsResolverTest {

    private val resolver = RegistrationHintsResolver(LocaleNegotiationService())

    @Test
    fun `keeps a supported time zone and resolves the language as before`() {
        assertThat(resolver.resolve("he", "en-US", "Asia/Jerusalem"))
            .isEqualTo(RegistrationHints(language = "he", timeZone = "Asia/Jerusalem"))
    }

    @Test
    fun `drops a time zone the settings would not accept`() {
        // Etc/ zones are excluded from the settings picker, so seeding one would leave the user
        // with a zone they can't select again.
        listOf("Etc/GMT+3", "Mars/Olympus_Mons", "", "x".repeat(500)).forEach { tz ->
            assertThat(resolver.resolveTimeZone(tz)).isNull()
        }
        assertThat(resolver.resolveTimeZone("UTC")).isEqualTo("UTC")
    }

    @Test
    fun `nothing in means no hints`() {
        assertThat(resolver.resolve(null, null, null)).isEqualTo(RegistrationHints.NONE)
    }
}
