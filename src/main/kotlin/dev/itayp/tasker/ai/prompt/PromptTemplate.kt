package dev.itayp.tasker.ai.prompt

/**
 * Tiny `{{var}}` substitution. Throws if any placeholder is left unfilled so that
 * accidental drift between template and caller surfaces loudly instead of producing
 * a half-rendered prompt the model has to guess about.
 */
class PromptTemplate(private val source: String) {

    private val placeholders: Set<String> = PLACEHOLDER_PATTERN
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()

    fun render(values: Map<String, String>): String {
        val unknown = values.keys - placeholders
        require(unknown.isEmpty()) { "Unknown template variables: $unknown" }
        var out = source
        for (key in placeholders) {
            val value = values[key]
                ?: throw IllegalArgumentException("Missing template variable: $key")
            out = out.replace("{{$key}}", value)
        }
        return out
    }

    fun placeholders(): Set<String> = placeholders

    companion object {
        private val PLACEHOLDER_PATTERN = Regex("""\{\{([a-zA-Z0-9_]+)\}\}""")
    }
}
