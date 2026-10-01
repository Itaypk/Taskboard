package dev.itayp.tasker.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ProductionConfigValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = ["https://backlog.fyi", "https://tasks.example.com", "http://localhost:8080"])
    fun `accepts absolute http(s) URLs`(url: String) {
        assertThat(ProductionConfigValidator.validateBaseUrl(url)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  ", "tasks.example.com", "ftp://tasks.example.com", "https://tasks.example.com/", "\${TASKER_APP_BASE_URL}"])
    fun `rejects blank, relative, non-http, trailing-slash and unresolved values`(url: String) {
        assertThat(ProductionConfigValidator.validateBaseUrl(url)).isNotNull()
    }

    @Test
    fun `refuses to start without a base URL`() {
        val validator = ProductionConfigValidator(AppProperties(baseUrl = ""))
        assertThatThrownBy { validator.afterPropertiesSet() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("TASKER_APP_BASE_URL")
    }
}
