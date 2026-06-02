package dev.itayp.tasker.channel

/**
 * A markup capability a [MessageFormatter] may support. The model is told which features the
 * active channel supports, and how each one looks, so it emits the right markup instead of
 * guessing (e.g. Markdown that Telegram renders literally).
 */
enum class FormattingFeature {
    BOLD,
    ITALIC,
    BULLET_LIST;

    /** Describes this feature to the model using the formatter's real markup as the example. */
    fun describe(f: MessageFormatter): String = when (this) {
        BOLD -> "Bold for emphasis or labels, e.g. ${f.bold("important")}."
        ITALIC -> "Italics for asides, e.g. ${f.italic("maybe")}."
        BULLET_LIST -> "Lists: one item per line, e.g.\n${f.bulletList(listOf("first", "second"))}"
    }
}

/**
 * Renders text for a specific conversation channel. The two distinct consumers are:
 *  - static strings we build ourselves (via [bold]/[italic]/[bulletList]/[escape]);
 *  - the AI assistant, which is handed [promptGuidance] so its free-form output matches the
 *    channel's supported markup.
 *
 * Implementations escape their arguments, so callers pass raw text.
 */
interface MessageFormatter {
    val features: Set<FormattingFeature>

    fun bold(text: String): String
    fun italic(text: String): String
    fun bulletList(items: List<String>): String

    /** Escapes text that would otherwise be interpreted as markup by the channel. */
    fun escape(text: String): String

    /** AI-facing formatting guidance, auto-derived from [features]. */
    fun promptGuidance(): String =
        if (features.isEmpty()) {
            "Write in plain text only. Do not use Markdown or HTML markup; " +
                "write any special characters literally."
        } else buildString {
            appendLine("Format messages with the following markup ONLY (never Markdown):")
            features.forEach { appendLine("- ${it.describe(this@MessageFormatter)}") }
            append("Escape any literal special characters that would otherwise be read as markup.")
        }
}

/**
 * Formatter for channels that parse a limited subset of HTML — currently Telegram, which sends
 * messages with `parse_mode=HTML` and supports `<b>`, `<i>`, etc., but no list tags.
 */
object HtmlMessageFormatter : MessageFormatter {
    override val features = setOf(FormattingFeature.BOLD, FormattingFeature.ITALIC, FormattingFeature.BULLET_LIST)

    override fun bold(text: String) = "<b>${escape(text)}</b>"

    override fun italic(text: String) = "<i>${escape(text)}</i>"

    // Telegram HTML has no list tags; render a literal bullet per line.
    override fun bulletList(items: List<String>) = items.joinToString("\n") { "• ${escape(it)}" }

    override fun escape(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

/**
 * Formatter for channels that render messages as Markdown — currently the web planning channel,
 * whose frontend renders message text through `MarkdownRenderer` (marked + DOMPurify). Maps the
 * supported features onto standard Markdown so the model emits `**bold**`, `*italic*`, and `-`
 * bullet lists. [escape] backslash-escapes the inline markers in text WE build (e.g. the capacity
 * prompt); the model's own free-form `say` output flows through unescaped and is sanitized by
 * DOMPurify on the frontend.
 */
object MarkdownMessageFormatter : MessageFormatter {
    override val features = setOf(FormattingFeature.BOLD, FormattingFeature.ITALIC, FormattingFeature.BULLET_LIST)

    override fun bold(text: String) = "**${escape(text)}**"

    override fun italic(text: String) = "*${escape(text)}*"

    override fun bulletList(items: List<String>) = items.joinToString("\n") { "- ${escape(it)}" }

    override fun escape(text: String) = text
        .replace("\\", "\\\\")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("`", "\\`")
        .replace("[", "\\[")
}

/**
 * Formatter for channels that render messages as plain text (the dev in-memory channel, whose
 * frontend prints via `textContent`). No markup is interpreted, so emphasis is dropped and
 * escaping is a no-op.
 */
object PlainTextMessageFormatter : MessageFormatter {
    override val features = emptySet<FormattingFeature>()

    override fun bold(text: String) = text

    override fun italic(text: String) = text

    override fun bulletList(items: List<String>) = items.joinToString("\n") { "• $it" }

    override fun escape(text: String) = text
}
