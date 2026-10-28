package dev.itayp.tasker.config

import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.ResourceBundleMessageSource
import java.util.ResourceBundle

/**
 * The message bundles say `@APP_NAME@` and `@APP_URL@` wherever the product name or its public URL
 * appears, and this source swaps in the configured values. The swap happens on the raw bundle
 * string — before `MessageFormat` sees it — so it works the same with and without arguments, and
 * one set of translations serves every instance.
 *
 * Declared as the `messageSource` bean, which makes Spring Boot's auto-configured one step aside;
 * the settings mirror what `spring.messages.*` configured before (basename `messages`, UTF-8), except
 * that it never falls back to the system locale.
 *
 * `messages_en_GB.properties` is a partial bundle: it holds only the keys whose British spelling
 * differs, and `ResourceBundle` parent chaining fills in the rest from the base bundle.
 */
class BrandedMessageSource(appProperties: AppProperties) : ResourceBundleMessageSource() {

    private val appName = appProperties.name
    private val appUrl = appProperties.baseUrl

    init {
        setBasename("messages")
        setDefaultEncoding(Charsets.UTF_8.name())
        // A locale without its own bundle (en-US) must fall through to the base English bundle, not
        // to whatever the server's default locale happens to have (a German JVM would answer in German).
        setFallbackToSystemLocale(false)
    }

    override fun getStringOrNull(bundle: ResourceBundle, key: String): String? =
        super.getStringOrNull(bundle, key)?.let(::brand)

    internal fun brand(text: String): String =
        text.replace(APP_NAME_TOKEN, appName).replace(APP_URL_TOKEN, appUrl)

    companion object {
        const val APP_NAME_TOKEN = "@APP_NAME@"
        const val APP_URL_TOKEN = "@APP_URL@"
    }
}

@Configuration
class BrandedMessageSourceConfiguration {

    @Bean
    fun messageSource(appProperties: AppProperties): MessageSource = BrandedMessageSource(appProperties)
}
