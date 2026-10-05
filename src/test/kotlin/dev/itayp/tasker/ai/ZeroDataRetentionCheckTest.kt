package dev.itayp.tasker.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper

@ExtendWith(OutputCaptureExtension::class)
class ZeroDataRetentionCheckTest {

    private val restClientBuilder = RestClient.builder().baseUrl(BASE_URL)
    private val server = MockRestServiceServer.bindTo(restClientBuilder).build()

    private fun check(aiProperties: AiProperties) =
        ZeroDataRetentionCheck(aiProperties, JsonMapper.builder().build(), restClientBuilder.build()).check()

    private fun properties(zeroDataRetention: Boolean = true, vararg models: String) = AiProperties(
        apiKey = "key",
        baseUrl = BASE_URL,
        weeklyPlanningModel = models.getOrElse(0) { "" },
        taskAssistantModel = models.getOrElse(1) { "" },
        zeroDataRetention = zeroDataRetention,
    )

    private fun respondWithZdrModels(vararg models: String) {
        val data = models.joinToString(",") { """{"model_id":"$it","provider_name":"Google"}""" }
        server.expect(requestTo("$BASE_URL/endpoints/zdr"))
            .andRespond(withSuccess("""{"data":[$data]}""", MediaType.APPLICATION_JSON))
    }

    @Test
    fun `names the configured models that have no ZDR endpoint`(output: CapturedOutput) {
        respondWithZdrModels("google/gemini-3.5-flash-lite", "openai/gpt-oss-20b")

        check(properties(models = arrayOf("google/gemini-3.5-flash-lite", "openai/gpt-oss-20b:free")))

        server.verify()
        assertThat(output).contains("no zero-data-retention endpoint", "[openai/gpt-oss-20b:free]")
            .doesNotContain("google/gemini-3.5-flash-lite]")
    }

    @Test
    fun `stays quiet when every configured model has a ZDR endpoint`(output: CapturedOutput) {
        respondWithZdrModels("google/gemini-3.5-flash-lite")

        check(properties(models = arrayOf("google/gemini-3.5-flash-lite")))

        server.verify()
        assertThat(output).doesNotContain("WARN")
    }

    @Test
    fun `a failed lookup is logged, not thrown`(output: CapturedOutput) {
        server.expect(requestTo("$BASE_URL/endpoints/zdr")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        check(properties(models = arrayOf("google/gemini-3.5-flash-lite")))

        assertThat(output).contains("Couldn't fetch OpenRouter's zero-data-retention endpoints")
    }

    @Test
    fun `skips the lookup when zero data retention is off`() {
        check(properties(zeroDataRetention = false, models = arrayOf("openai/gpt-oss-20b:free")))

        server.verify() // no request expected, none made
    }

    private companion object {
        const val BASE_URL = "https://openrouter.test/api/v1"
    }
}
