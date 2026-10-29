package dev.itayp.tasker.util

/**
 * Server-side counterpart of the frontend's `checklist.ts`: finds GFM task-list items in a note the
 * same way, so a box the board renders is exactly a box this sees. Keep the two patterns in sync.
 */
object MarkdownChecklist {

    // Prefix allows blockquote markers and indentation, for quoted and nested lists.
    private val TASK_ITEM = Regex("""^((?:[ \t]*>)*[ \t]*(?:[-*+]|\d{1,9}[.)])[ \t]+)\[([ xX])\](?= +\S)""")
    private val FENCE = Regex("""^[ \t]*(?:>[ \t]*)*(`{3,}|~{3,})""")

    /** Unticks every checklist item in [markdown], skipping fenced code blocks. */
    fun uncheckAll(markdown: String): String {
        val lines = markdown.split('\n').toMutableList()
        var fence: String? = null
        for (i in lines.indices) {
            val fenceMatch = FENCE.find(lines[i])
            if (fenceMatch != null) {
                val marker = fenceMatch.groupValues[1]
                val open = fence
                if (open == null) fence = marker
                else if (marker[0] == open[0] && marker.length >= open.length) fence = null
                continue
            }
            if (fence != null) continue
            val item = TASK_ITEM.find(lines[i]) ?: continue
            if (item.groupValues[2] != " ") {
                lines[i] = item.groupValues[1] + "[ ]" + lines[i].substring(item.range.last + 1)
            }
        }
        return lines.joinToString("\n")
    }
}
