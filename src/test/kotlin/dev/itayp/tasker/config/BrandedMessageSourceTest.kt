package dev.itayp.tasker.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.util.Locale

class BrandedMessageSourceTest {

    private val source = BrandedMessageSource(AppProperties(baseUrl = "https://tasks.acme.test", name = "Acme Tasks"))

    @Test
    fun `substitutes the name in messages without arguments`() {
        assertThat(source.getMessage("email.login.subject", null, Locale.ENGLISH)).isEqualTo("Sign in to Acme Tasks")
    }

    @Test
    fun `substitutes the name and URL in messages formatted with arguments`() {
        assertThat(source.getMessage("email.boardInvite.subject", arrayOf("Bob"), Locale.ENGLISH))
            .isEqualTo("Bob invited you to a board on Acme Tasks")
        assertThat(source.getMessage("email.footer.sent_by", null, Locale.ENGLISH))
            .isEqualTo("""Sent by <a href="https://tasks.acme.test">Acme Tasks</a>""")
    }

    @Test
    fun `substitutes in translated bundles too`() {
        assertThat(source.getMessage("email.boardInvite.subject", arrayOf("Bob"), Locale.GERMAN))
            .isEqualTo("Bob hat dich zu einem Board bei Acme Tasks eingeladen")
    }

    /** A hard-coded name in a bundle would show the hosted product's name on every other instance. */
    @Test
    fun `no bundle hard-codes the hosted product name or URL`() {
        val bundles = PathMatchingResourcePatternResolver().getResources("classpath*:messages*.properties")
        assertThat(bundles).isNotEmpty()
        bundles.forEach { bundle ->
            val text = bundle.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            assertThat(text).withFailMessage("${bundle.filename} mentions backlog.fyi; use @APP_NAME@ / @APP_URL@")
                .doesNotContainIgnoringCase("backlog.fyi")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "Tom's Tasks", "<b>Tasks</b>", "Tasks {0}", "A & B"])
    fun `rejects names that would break HTML emails or message patterns`(name: String) {
        assertThatThrownBy { AppProperties(name = name) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
