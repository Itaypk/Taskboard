package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Telegram configuration. [botToken] / [botUsername] drive the bot messaging integration
 * (planning conversation), while the `client*` / `*Uri` fields drive the OIDC login flow
 * (https://core.telegram.org/widgets/login). The Client ID / Secret come from
 * BotFather → Bot Settings → Web Login.
 */
@ConfigurationProperties("tasker.telegram")
data class TelegramAuthProperties(
    val botToken: String = "",
    val botUsername: String = "",
    val enabled: Boolean = false,
    /** OAuth2/OIDC client id (the bot id) — also the expected `aud` claim of the id_token. */
    val clientId: String = "",
    /** OAuth2 client secret, used as HTTP Basic credentials for the token exchange. */
    val clientSecret: String = "",
    val authorizationUri: String = "https://oauth.telegram.org/auth",
    val tokenUri: String = "https://oauth.telegram.org/token",
    val jwkSetUri: String = "https://oauth.telegram.org/.well-known/jwks.json",
    val issuer: String = "https://oauth.telegram.org",
) {
    /** Telegram login needs the OIDC client credentials; the bot token alone isn't enough. */
    val loginConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}
