package dev.itayp.tasker.model.request

data class UpdateBacklogTaskRequest(
    val title: String,
    val description: String? = null,
    val url: String? = null,
    val priority: String? = null,
    val deadline: String? = null,
    val estimatedMinutes: Int? = null,
    val status: String = "todo",
    val categoryId: String,
    val tags: List<TagInput> = emptyList()
)
