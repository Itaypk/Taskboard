package dev.itayp.tasker.security.google

import dev.itayp.tasker.config.GoogleAuthProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Google sign-in via the OIDC Authorization Code + PKCE flow
 * (https://developers.google.com/identity/openid-connect/openid-connect): build the authorization
 * request, then on callback exchange the code for an `id_token` and validate it against Google's
 * JWKS ([GoogleAuthConfiguration.googleJwtDecoder]).
 *
 * Only `openid email` is requested (nothing from `profile` is used). Those scopes are non-sensitive, so the consent screen
 * needs no Google review; anything broader (e.g. Calendar) belongs in a separate, incremental
 * consent rather than here, or every sign-in would need it.
 */
@Component
class GoogleOidcService(
    private val properties: GoogleAuthProperties,
    @Qualifier("googleJwtDecoder") private val jwtDecoder: JwtDecoder,
    @Qualifier("googleTokenRestClient") private val tokenRestClient: RestClient,
) {
    private val random = SecureRandom()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun isConfigured(): Boolean = properties.loginConfigured

    fun buildAuthorizationRequest(redirectUri: String): GoogleAuthorizationRequest {
        val state = randomUrlSafe()
        val codeVerifier = randomUrlSafe()
        val url = buildString {
            append(properties.authorizationUri)
            append("?client_id=").append(enc(properties.clientId))
            append("&redirect_uri=").append(enc(redirectUri))
            append("&response_type=code")
            append("&scope=").append(enc("openid email"))
            append("&state=").append(enc(state))
            append("&code_challenge=").append(enc(s256(codeVerifier)))
            append("&code_challenge_method=S256")
            // Let someone with several Google accounts pick one, instead of silently reusing the active one.
            append("&prompt=select_account")
        }
        return GoogleAuthorizationRequest(authorizationUrl = url, state = state, codeVerifier = codeVerifier)
    }

    fun completeAuthorization(code: String, codeVerifier: String, redirectUri: String): GoogleAuthData =
        verifyIdToken(exchangeCodeForIdToken(code, codeVerifier, redirectUri))

    private fun exchangeCodeForIdToken(code: String, codeVerifier: String, redirectUri: String): String {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("code", code)
            add("redirect_uri", redirectUri)
            add("client_id", properties.clientId)
            add("client_secret", properties.clientSecret)
            add("code_verifier", codeVerifier)
        }
        val body = try {
            tokenRestClient.post()
                .uri(properties.tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map::class.java)
        } catch (e: RestClientException) {
            throw GoogleAuthException("Token exchange failed: ${e.message}")
        }
        val idToken = body?.get("id_token") as? String
        return idToken?.takeIf { it.isNotBlank() } ?: throw GoogleAuthException("Token response missing id_token")
    }

    /** Validates the id_token (signature, issuer, audience, expiry) and maps its claims. */
    fun verifyIdToken(idToken: String): GoogleAuthData {
        val jwt = try {
            jwtDecoder.decode(idToken)
        } catch (e: JwtException) {
            throw GoogleAuthException("Invalid id_token: ${e.message}")
        }
        val subject = jwt.subject?.takeIf { it.isNotBlank() } ?: throw GoogleAuthException("id_token missing sub")
        return GoogleAuthData(
            subject = subject,
            email = jwt.getClaimAsString("email"),
            emailVerified = jwt.getClaim<Any>("email_verified").let { it == true || it == "true" },
            hostedDomain = jwt.getClaimAsString("hd"),
        )
    }

    private fun randomUrlSafe(): String = urlEncoder.encodeToString(ByteArray(32).also(random::nextBytes))

    private fun s256(verifier: String): String =
        urlEncoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

data class GoogleAuthorizationRequest(
    val authorizationUrl: String,
    val state: String,
    val codeVerifier: String,
)

/** The claims we use from a validated Google id_token. [subject] (`sub`) is the stable account key; the email can change. */
data class GoogleAuthData(
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    /** The Workspace domain (`hd`), present only for Google Workspace accounts. */
    val hostedDomain: String?,
) {
    /**
     * The email, normalized, when Google's `email_verified` can stand in for proof of ownership *now*.
     * That holds for Gmail addresses and Workspace accounts, whose addresses Google itself controls.
     * For any other Google account, `email_verified` only says the address was confirmed when the
     * account was created, so someone who has since lost the mailbox would still carry the flag.
     */
    val trustedEmail: String?
        get() {
            val normalized = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (!emailVerified) return null
            val domain = normalized.substringAfterLast('@')
            return normalized.takeIf { hostedDomain != null || domain == "gmail.com" || domain == "googlemail.com" }
        }
}

class GoogleAuthException(message: String) : RuntimeException(message)
