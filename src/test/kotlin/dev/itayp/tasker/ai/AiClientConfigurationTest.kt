package dev.itayp.tasker.ai

import dev.itayp.nescioquid.openrouter.ApiDialect
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiClientConfigurationTest {

    private fun compatible(reasoning: Boolean = false, visionInputs: Set<String> = emptySet(), visionModel: String = "") = AiProperties(
        provider = AiProvider.OPENAI_COMPATIBLE,
        baseUrl = "http://localhost:8000/v1",
        weeklyPlanningModel = "big",
        taskAssistantModel = "small",
        visionModel = visionModel,
        transcriptionModel = "whisper",
        openaiCompatible = OpenAiCompatibleAiProperties(reasoning = reasoning, visionInputs = visionInputs),
    )

    @Test
    fun `OpenRouter declares nothing, so capabilities are fetched`() {
        val properties = AiProperties(apiKey = "k", weeklyPlanningModel = "a/b")
        val client = AiClientConfiguration().aiClientProperties(properties)

        assertEquals(ApiDialect.OPENROUTER, client.dialect)
        assertTrue(client.declaredCapabilities.isEmpty())
        assertEquals(OPENROUTER_BASE_URL, client.baseUrl)
    }

    @Test
    fun `an OpenAI-compatible server gets text-only, non-reasoning capabilities by default`() {
        val client = AiClientConfiguration().aiClientProperties(compatible())

        assertEquals(ApiDialect.OPENAI_COMPATIBLE, client.dialect)
        assertEquals(false, client.zeroDataRetention)
        assertEquals(setOf("big", "small"), client.declaredCapabilities.keys) // never the transcription model
        client.declaredCapabilities.values.forEach {
            assertEquals(false, it.supportsReasoning)
            assertEquals(listOf("text"), it.inputModalities)
        }
    }

    @Test
    fun `the operator's reasoning and vision inputs reach the right models`() {
        val declared = AiClientConfiguration.declaredCapabilities(compatible(reasoning = true, visionInputs = setOf("image", " PDF ", "video")))

        assertTrue(declared.values.all { it.supportsReasoning })
        // The vision model falls back to the task-assistant model; an unknown input is ignored.
        assertEquals(listOf("text", "image", "file"), declared.getValue("small").inputModalities)
        assertEquals(listOf("text"), declared.getValue("big").inputModalities)
    }

    @Test
    fun `a dedicated vision model gets the vision inputs instead`() {
        val declared = AiClientConfiguration.declaredCapabilities(compatible(visionInputs = setOf("image"), visionModel = "eyes"))

        assertEquals(listOf("text", "image"), declared.getValue("eyes").inputModalities)
        assertEquals(listOf("text"), declared.getValue("small").inputModalities)
    }
}
