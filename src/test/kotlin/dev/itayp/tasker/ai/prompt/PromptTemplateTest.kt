package dev.itayp.tasker.ai.prompt

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PromptTemplateTest {

    private fun compile(source: String) = PromptTemplate.compile(source)

    @Test
    fun `renders with all variables filled`() {
        val result = compile("Hello {{name}}, you have {{count}} tasks.")
            .render(mapOf("name" to "Itay", "count" to "3"))
        assertEquals("Hello Itay, you have 3 tasks.", result)
    }

    @Test
    fun `repeated placeholders are all replaced`() {
        val result = compile("{{x}} + {{x}} = {{y}}")
            .render(mapOf("x" to "1", "y" to "2"))
        assertEquals("1 + 1 = 2", result)
    }

    @Test
    fun `if block renders when condition is true`() {
        val result = compile("{{#if show}}visible{{/if}}")
            .render(mapOf("show" to true))
        assertEquals("visible", result)
    }

    @Test
    fun `if block is skipped when condition is false`() {
        val result = compile("{{#if show}}visible{{/if}}")
            .render(mapOf("show" to false))
        assertEquals("", result)
    }

    @Test
    fun `if-else block renders else branch when condition is false`() {
        val result = compile("{{#if flag}}yes{{else}}no{{/if}}")
            .render(mapOf("flag" to false))
        assertEquals("no", result)
    }

    @Test
    fun `if block treats non-empty string as truthy`() {
        val result = compile("{{#if value}}present{{/if}}")
            .render(mapOf("value" to "hello"))
        assertEquals("present", result)
    }

    @Test
    fun `if block treats empty string as falsy`() {
        val result = compile("{{#if value}}present{{/if}}")
            .render(mapOf("value" to ""))
        assertEquals("", result)
    }
}
