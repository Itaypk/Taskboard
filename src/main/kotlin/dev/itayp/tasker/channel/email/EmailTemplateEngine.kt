package dev.itayp.tasker.channel.email

import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.Helper
import com.github.jknack.handlebars.Template
import org.springframework.context.MessageSource
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads and renders Handlebars templates specifically for emails.
 * Registers a `message` helper to integrate with Spring's MessageSource for i18n support.
 */
@Component
class EmailTemplateEngine(
    private val messageSource: MessageSource
) {
    private val handlebars = Handlebars().apply {
        registerHelper("message", Helper<String> { context, options ->
            val model = options.context.model() as? Map<*, *>
            val locale = model?.get("locale") as? Locale ?: Locale.ENGLISH
            val args = options.params.map { it }.toTypedArray()
            messageSource.getMessage(context, args, locale)
        })
    }

    private val cache = ConcurrentHashMap<String, Template>()

    fun render(path: String, model: Map<String, Any?>, locale: Locale): String {
        val template = cache.computeIfAbsent(path) {
            val resource = ClassPathResource(path)
            require(resource.exists()) { "Email template not found: $path" }
            val source = resource.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            handlebars.compileInline(source)
        }
        
        val fullModel = model.toMutableMap()
        fullModel["locale"] = locale
        return template.apply(fullModel)
    }
}
