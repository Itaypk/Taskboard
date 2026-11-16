package dev.itayp.tasker.security.google

import dev.itayp.tasker.config.GoogleAuthProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.web.client.RestClient

/**
 * Beans for [GoogleOidcService]. Named (`googleJwtDecoder`, `googleTokenRestClient`) so they never
 * collide with the Telegram module's beans or a future resource-server decoder.
 */
@Configuration
class GoogleAuthConfiguration {

    @Bean
    fun googleJwtDecoder(properties: GoogleAuthProperties): JwtDecoder {
        val decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri).build()
        val validators = buildList {
            add(JwtTimestampValidator())
            // Google documents both spellings of its issuer, so the stock single-issuer validator won't do.
            add(JwtClaimValidator<Any>(JwtClaimNames.ISS) { it.toString() in GOOGLE_ISSUERS })
            // Only enforced once configured, so an instance without Google login still boots.
            if (properties.clientId.isNotBlank()) {
                add(JwtClaimValidator<List<String>>(JwtClaimNames.AUD) { aud -> aud.contains(properties.clientId) })
            }
        }
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(validators))
        return decoder
    }

    @Bean
    fun googleTokenRestClient(): RestClient = RestClient.builder().build()

    companion object {
        val GOOGLE_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
    }
}
