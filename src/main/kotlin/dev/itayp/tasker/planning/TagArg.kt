package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A single tag as supplied by the model — to `create_task` / `update_task`, or inside a
 * [TaskDraft] from the suggestion sub-agent: reuse an existing tag by `id`, or create one from
 * `label` (+ optional `color_id`). Shared so every place that reads model-authored tags parses them
 * the same way. Unlike the tools' top-level arguments — where presence detection matters for partial
 * updates — a tag element has no partial semantics, so a plain data class is the right fit here.
 */
data class TagArg(
    val id: String? = null,
    val label: String,
    @JsonProperty("color_id") val colorId: String? = null,
)
