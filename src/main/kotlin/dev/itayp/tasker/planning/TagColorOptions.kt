package dev.itayp.tasker.planning

import dev.itayp.tasker.model.TagColor

/**
 * Shared tag-color handling for the task tools (`create_task` / `update_task`). The model supplies
 * `color_id` as a free-form string, so both tools expose the same closed set of allowed values and
 * resolve an input to a valid color (falling back to a random one) rather than letting an unexpected
 * value fail persistence.
 */
object TagColorOptions {

    val ALLOWED: List<String> = TagColor.entries.map { it.name.lowercase() }

    fun resolve(colorId: String?): String =
        TagColor.entries.firstOrNull { it.name.equals(colorId, ignoreCase = true) }?.name?.lowercase()
            ?: TagColor.entries.random().name.lowercase()
}
