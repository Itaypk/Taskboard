package dev.itayp.tasker.ai.tool

interface AiTool {
    val name: String
    val description: String

    /**
     * JSON Schema object describing the function parameters.
     * Example: mapOf("type" to "object", "properties" to mapOf(...), "required" to listOf(...))
     */
    val parameters: Map<String, Any>

    /** Receives the arguments JSON string from the model; returns the result as a string. */
    fun execute(arguments: String): String
}
