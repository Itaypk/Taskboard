package dev.itayp.tasker.controller

import dev.itayp.tasker.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ContentPageRendererTest {

    private val renderer = ContentPageRenderer(
        AppProperties(
            baseUrl = "https://tasks.acme.test",
            name = "Acme Tasks",
            supportEmail = "help@acme.test",
            abuseEmail = "abuse@acme.test",
        ),
    )

    @Test
    fun `fills in the instance's name, URL and contact addresses`() {
        assertThat(renderer.fillPlaceholders("{{APP_NAME}} at {{APP_URL}}: {{SUPPORT_EMAIL}}, {{ABUSE_EMAIL}}"))
            .isEqualTo("Acme Tasks at https://tasks.acme.test: help@acme.test, abuse@acme.test")
    }

    @ParameterizedTest
    @EnumSource(ContentPage::class)
    fun `renders every page's markdown with no placeholder left`(page: ContentPage) {
        val body = renderer.renderBody(page)

        assertThat(body).isNotNull().contains("<h2>", "Acme Tasks").doesNotContain("{{", "Backlog.fyi")
        assertThat(renderer.fillPlaceholders(page.description)).contains("Acme Tasks").doesNotContain("{{")
    }

    @Test
    fun `every page path is unique and resolves back to its page`() {
        ContentPage.entries.forEach { assertThat(ContentPage.forPath(it.path)).isEqualTo(it) }
        assertThat(ContentPage.forPath("/settings")).isNull()
    }
}
