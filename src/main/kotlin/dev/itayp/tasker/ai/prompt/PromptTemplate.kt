package dev.itayp.tasker.ai.prompt

import com.github.jknack.handlebars.EscapingStrategy
import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.Template

class PromptTemplate(private val template: Template) {

    fun render(values: Map<String, Any?>): String = template.apply(values)

    companion object {
        private val handlebars = Handlebars().with(EscapingStrategy.NOOP)

        fun compile(source: String): PromptTemplate = PromptTemplate(handlebars.compileInline(source))
    }
}
