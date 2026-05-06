package dev.itayp.tasker.ai.prompt

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PromptTemplateTest {

    @Test
    fun `renders with all placeholders filled`() {
        val template = PromptTemplate("Hello {{name}}, you have {{count}} tasks.")
        val result = template.render(mapOf("name" to "Itay", "count" to "3"))
        assertEquals("Hello Itay, you have 3 tasks.", result)
    }

    @Test
    fun `repeated placeholders are all replaced`() {
        val template = PromptTemplate("{{x}} + {{x}} = {{y}}")
        assertEquals("1 + 1 = 2", template.render(mapOf("x" to "1", "y" to "2")))
    }

    @Test
    fun `missing variable throws`() {
        val template = PromptTemplate("Hi {{name}}")
        assertFailsWith<IllegalArgumentException> { template.render(emptyMap()) }
    }

    @Test
    fun `unknown variable throws`() {
        val template = PromptTemplate("Hi {{name}}")
        assertFailsWith<IllegalArgumentException> {
            template.render(mapOf("name" to "x", "stranger" to "y"))
        }
    }

    @Test
    fun `placeholders are detected`() {
        val template = PromptTemplate("{{a}} {{b}} {{a}}")
        assertEquals(setOf("a", "b"), template.placeholders())
    }
}
