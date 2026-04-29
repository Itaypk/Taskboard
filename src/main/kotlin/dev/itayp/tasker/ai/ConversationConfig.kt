package dev.itayp.tasker.ai

data class ConversationConfig(
    val conversationType: String,
    val model: String,
    val temperature: Double?,
    val systemPrompt: String,
    val ttlDays: Int = 7,
)
