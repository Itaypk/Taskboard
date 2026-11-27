package dev.itayp.tasker.channel.whatsapp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.reader

/**
 * The quick-add card's fixed buttons must fit WhatsApp's 20-character button titles in every
 * language, or the card silently degrades to a list (`docs/MESSAGING-CHANNELS.md`, section 5).
 * The day and time-of-day pickers aren't checked: they usually have four or more options, so they
 * are lists anyway, where rows have room for 24 characters and longer labels are cut.
 */
class WhatsAppButtonLabelsTest {

    private val keys = listOf(
        "quickadd.button.save", "quickadd.button.adjust", "quickadd.button.cancel", "quickadd.button.explain",
        "quickadd.plan.skip",
    )

    @Test
    fun `every quick-add button label fits a WhatsApp button in every bundle`() {
        // The bundles sit in one directory on the test classpath; a root-level wildcard can't find them.
        val dir = Path.of(checkNotNull(javaClass.classLoader.getResource("messages.properties")).toURI()).parent
        val bundles = dir.listDirectoryEntries("messages*.properties").sortedBy { it.name }
        assertThat(bundles).hasSizeGreaterThan(1)

        val tooLong = bundles.flatMap { resource ->
            val properties = Properties().apply { resource.reader(Charsets.UTF_8).use { load(it) } }
            val labels = keys.mapNotNull { key -> properties.getProperty(key)?.let { key to it } }
            labels.filter { (_, label) -> label.length > WhatsAppConversationChannel.BUTTON_TITLE_LIMIT }
                .map { (key, label) -> "${resource.name} $key: $label (${label.length})" }
        }

        assertThat(tooLong).isEmpty()
    }
}
