package dev.itayp.tasker.ai.prompt

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class ExternalContentTest {

    private val block = Regex(
        """<external-content source="([^"]+)" guard="([0-9a-f]{16})">\n(.*)\n</external-content guard="\2">""",
        RegexOption.DOT_MATCHES_ALL,
    )

    @Test
    fun `content sits between an opening and a closing tag with the same guard`() {
        val match = assertNotNull(block.matchEntire(ExternalContent.wrap("email", "Subject: hi\nbody\n")))

        assertEquals("email", match.groupValues[1])
        // A trailing newline doesn't leave a blank line before the closing tag.
        assertEquals("Subject: hi\nbody", match.groupValues[3])
    }

    /** A guard that repeated would be one the content could learn and close early. */
    @Test
    fun `every block gets a fresh guard`() {
        val first = assertNotNull(block.matchEntire(ExternalContent.wrap("tasks", "a")))
        val second = assertNotNull(block.matchEntire(ExternalContent.wrap("tasks", "a")))

        assertNotEquals(first.groupValues[2], second.groupValues[2])
    }
}
