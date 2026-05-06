package dev.itayp.tasker.ai.prompt

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads templates from the classpath and caches the parsed result.
 * Templates are versioned with the code under `src/main/resources/`.
 *
 * [load] is a convenience wrapper that resolves relative to `classpath:prompts/`.
 * [loadFromClasspath] accepts a full classpath path for templates outside that root.
 */
@Component
class PromptTemplateLoader {

    private val cache = ConcurrentHashMap<String, PromptTemplate>()

    /** Loads a template relative to `classpath:prompts/` (e.g. `weekly-planning/system.md`). */
    fun load(path: String): PromptTemplate = loadFromClasspath("prompts/$path")

    /** Loads a template by its full classpath path (e.g. `emails/invitation.html`). */
    fun loadFromClasspath(path: String): PromptTemplate = cache.computeIfAbsent(path) {
        val resource = ClassPathResource(path)
        require(resource.exists()) { "Template not found: $path" }
        val source = resource.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        PromptTemplate(source)
    }
}
