package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * "Sign in with Google" (OIDC Authorization Code + PKCE). The Client ID / Secret come from a
 * Google Cloud project's Google Auth Platform → Clients (type "Web application"), with
 * `<base-url>/api/auth/google/callback` as an authorized redirect URI. The endpoint URIs are
 * Google's published ones and only overridable for tests.
 */
@ConfigurationProperties("tasker.google")
data class GoogleAuthProperties(
    /** OAuth client id, also the expected `aud` claim of the id_token. */
    val clientId: String = "",
    val clientSecret: String = "",
    val authorizationUri: String = "https://accounts.google.com/o/oauth2/v2/auth",
    val tokenUri: String = "https://oauth2.googleapis.com/token",
    val jwkSetUri: String = "https://www.googleapis.com/oauth2/v3/certs",
) {
    val loginConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}
