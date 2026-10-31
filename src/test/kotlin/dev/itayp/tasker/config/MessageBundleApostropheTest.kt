package dev.itayp.tasker.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.InputStreamReader
import java.util.Properties

/**
 * Spring only runs a message through `MessageFormat` when it has arguments, and `''` is a
 * `MessageFormat` escape. So a message with placeholders must write an apostrophe as `''` (a lone
 * `'` starts a quoted section and swallows the text after it), and one without placeholders must
 * write a plain `'` (an `''` comes out doubled, as it did in the sign-in email).
 *
 * This assumes a message without placeholders is never formatted with arguments, which holds for
 * every caller today.
 */
class MessageBundleApostropheTest {

    @Test
    fun `apostrophes are escaped exactly when the message is formatted`() {
        val bundles = PathMatchingResourcePatternResolver().getResources("classpath*:messages*.properties")
        assertThat(bundles).isNotEmpty

        val violations = bundles.flatMap { bundle ->
            val properties = Properties().apply {
                bundle.inputStream.use { load(InputStreamReader(it, Charsets.UTF_8)) }
            }
            properties.stringPropertyNames().sorted().mapNotNull { key ->
                val value = properties.getProperty(key)
                val formatted = PLACEHOLDER.containsMatchIn(value)
                when {
                    formatted && LONE_APOSTROPHE.containsMatchIn(value) -> "${bundle.filename} $key: needs '' (has placeholders)"
                    !formatted && value.contains("''") -> "${bundle.filename} $key: needs ' (no placeholders)"
                    else -> null
                }
            }
        }

        assertThat(violations).isEmpty()
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{\d+""")
        val LONE_APOSTROPHE = Regex("""(?<!')'(?!')""")
    }
}
