package dev.itayp.tasker.ai.client

import dev.itayp.tasker.ai.AiProperties
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCapabilityServiceTest {

    private fun service(properties: AiProperties): Pair<ModelCapabilityService, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return ModelCapabilityService(properties, builder) to server
    }

    @Test
    fun `caches reasoning support from supported_parameters`() {
        val properties = AiProperties(apiKey = "k", weeklyPlanningModel = "openai/gpt-oss-20b:free")
        val (svc, server) = service(properties)
        server.expect(requestTo("https://openrouter.ai/api/v1/model/openai/gpt-oss-20b:free"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """{"data":{"supported_parameters":["temperature","reasoning","reasoning_effort"]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        svc.prefetch()

        server.verify()
        assertTrue(svc.supportsReasoning("openai/gpt-oss-20b:free"))
        // No `reasoning` object in this response → efforts/default unknown.
        assertNull(svc.get("openai/gpt-oss-20b:free")?.supportedEfforts)
        assertNull(svc.get("openai/gpt-oss-20b:free")?.defaultEffort)
    }

    @Test
    fun `captures the reasoning object and input modalities`() {
        val properties = AiProperties(apiKey = "k", weeklyPlanningModel = "google/gemini-3.1-flash-lite")
        val (svc, server) = service(properties)
        server.expect(requestTo("https://openrouter.ai/api/v1/model/google/gemini-3.1-flash-lite"))
            .andRespond(
                withSuccess(
                    """
                    {"data":{
                      "supported_parameters":["reasoning","reasoning_effort","temperature"],
                      "architecture":{"input_modalities":["text","image","audio"]},
                      "reasoning":{"mandatory":false,"default_enabled":true,
                        "supported_efforts":["high","medium","low","minimal"],"default_effort":"minimal"}
                    }}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        svc.prefetch()

        val caps = svc.get("google/gemini-3.1-flash-lite")
        assertTrue(caps!!.supportsReasoning)
        assertEquals(listOf("high", "medium", "low", "minimal"), caps.supportedEfforts)
        assertEquals("minimal", caps.defaultEffort)
        assertTrue(caps.reasoningDefaultEnabled)
        assertFalse(caps.reasoningMandatory)
        assertEquals(listOf("text", "image", "audio"), caps.inputModalities)
    }

    @Test
    fun `model without reasoning in supported_parameters is not supported`() {
        val properties = AiProperties(apiKey = "k", weeklyPlanningModel = "some/model")
        val (svc, server) = service(properties)
        server.expect(requestTo("https://openrouter.ai/api/v1/model/some/model"))
            .andRespond(
                withSuccess(
                    """{"data":{"supported_parameters":["temperature","max_tokens"]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        svc.prefetch()

        assertFalse(svc.supportsReasoning("some/model"))
    }

    @Test
    fun `fetch failure leaves the model unknown (not supported)`() {
        val properties = AiProperties(apiKey = "k", weeklyPlanningModel = "some/model")
        val (svc, server) = service(properties)
        server.expect(requestTo("https://openrouter.ai/api/v1/model/some/model"))
            .andRespond(withServerError())

        svc.prefetch()

        assertFalse(svc.supportsReasoning("some/model"))
    }

    @Test
    fun `blank api key skips prefetch entirely`() {
        val properties = AiProperties(apiKey = "", weeklyPlanningModel = "some/model")
        val (svc, server) = service(properties)
        // No request expected.

        svc.prefetch()

        server.verify()
        assertFalse(svc.supportsReasoning("some/model"))
    }

    @Test
    fun `unknown model defaults to not supported`() {
        val (svc, _) = service(AiProperties(apiKey = "k"))
        assertFalse(svc.supportsReasoning("never/fetched"))
    }
}
