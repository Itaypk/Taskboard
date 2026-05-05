package dev.itayp.tasker.ai.prompt

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads prompt templates from `classpath:prompts/...` and caches the parsed result.
 * Templates are expected to live alongside the code (e.g. `prompts/weekly-planning/system.md`)
 * so they're versioned with the rest of the app.
 */
@Component
class PromptTemplateLoader {

    private val cache = ConcurrentHashMap<String, PromptTemplate>()

    fun load(path: String): PromptTemplate = cache.computeIfAbsent(path) {
        val resource = ClassPathResource("prompts/$path")
        require(resource.exists()) { "Prompt template not found: prompts/$path" }
        val source = resource.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        PromptTemplate(source)
    }
}
