package dev.itayp.tasker.ai.prompt

import dev.itayp.tasker.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PromptTemplateLoaderTest {

    @Test
    fun `the assistant introduces itself by the configured name`() {
        val prompt = PromptTemplateLoader(AppProperties(name = "Acme Tasks"))
            .load("weekly-planning/system.md")
            .render(mapOf("display_name" to "Alice"))

        assertThat(prompt).startsWith("You are the Acme Tasks weekly planning assistant.")
        assertThat(prompt).doesNotContain("@APP_NAME@")
    }
}
