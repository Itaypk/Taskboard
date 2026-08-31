package dev.itayp.tasker.config

import dev.itayp.nescioquid.telegram.TelegramOidcProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

/**
 * Wires the Nescioquid `telegram-oidc-login` module to the app's own config. The library's
 * classes depend only on [TelegramOidcProperties]; the [telegramOidcProperties] bean maps the
 * app's [TelegramAuthProperties] onto that contract.
 *
 * The [ComponentScan] pulls in the library's `@Component`/`@Configuration` beans
 * (`TelegramOidcService`, plus the `telegramJwtDecoder` / `telegramTokenRestClient` beans from its
 * own `TelegramOidcConfiguration`) — they live outside the app's `dev.itayp.tasker` scan base
 * package, so they aren't picked up otherwise. Named distinctly from the library's own
 * `TelegramOidcConfiguration` class — both scanned into the same context, and Spring's default
 * annotation bean-naming collides on identical simple class names otherwise.
 */
@Configuration
@ComponentScan("dev.itayp.nescioquid.telegram")
class TelegramAuthConfiguration {

    @Bean
    fun telegramOidcProperties(properties: TelegramAuthProperties): TelegramOidcProperties =
        TelegramOidcProperties(
            clientId = properties.clientId,
            clientSecret = properties.clientSecret,
            authorizationUri = properties.authorizationUri,
            tokenUri = properties.tokenUri,
            jwkSetUri = properties.jwkSetUri,
            issuer = properties.issuer,
        )
}
