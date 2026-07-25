package dev.itayp.tasker.planning

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * A single tag as supplied by the model — to `create_task` / `update_task`, or inside a
 * [TaskDraft] from the suggestion sub-agent: reuse an existing tag by `id`, or create one from
 * `label` (+ optional `color_id`). Shared so every place that reads model-authored tags parses them
 * the same way. Unlike the tools' top-level arguments — where presence detection matters for partial
 * updates — a tag element has no partial semantics, so a plain data class is the right fit here.
 */
data class TagArg(
    @JsonPropertyDescription("UUID of an existing tag, if reusing one.")
    val id: String? = null,
    @JsonPropertyDescription("Tag label.")
    val label: String,
    @JsonProperty("color_id")
    @JsonPropertyDescription("Color for a new tag. One of the allowed values; a color is assigned if omitted.")
    val colorId: String? = null,
)
