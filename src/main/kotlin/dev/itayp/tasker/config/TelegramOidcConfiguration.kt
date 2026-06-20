package dev.itayp.tasker.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.*
import org.springframework.web.client.RestClient

/**
 * Beans for validating Telegram id_tokens. The decoder fetches Telegram's public keys lazily
 * from the JWKS endpoint (no network at startup) and enforces signature + issuer + expiry, plus
 * an audience check against our bot's Client ID.
 */
@Configuration
class TelegramOidcConfiguration {

    @Bean
    fun telegramJwtDecoder(properties: TelegramAuthProperties): JwtDecoder {
        val decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri).build()
        val validators = buildList {
            add(JwtValidators.createDefaultWithIssuer(properties.issuer))
            // Only enforce the audience when configured, so dev/test (no Client ID) can still boot.
            if (properties.clientId.isNotBlank()) {
                add(JwtClaimValidator<List<String>>(JwtClaimNames.AUD) { aud ->
                    aud.contains(properties.clientId)
                })
            }
        }
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(validators))
        return decoder
    }

    /** Client for the Telegram token exchange. Built standalone via [RestClient.create] (rather than
     *  an autoconfigured `RestClient.Builder`, which isn't present in this context) — it still uses
     *  the framework's default message converters, including the project's Jackson, for the token JSON.
     *  Isolated as its own bean to keep it easy to mock in tests. */
    @Bean
    fun telegramTokenRestClient(): RestClient = RestClient.create()
}
