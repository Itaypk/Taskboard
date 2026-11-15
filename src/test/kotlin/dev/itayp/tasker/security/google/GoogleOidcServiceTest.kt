package dev.itayp.tasker.security.google

import dev.itayp.tasker.config.GoogleAuthProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.client.RestClient
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class GoogleOidcServiceTest {

    private val decoder = mock<JwtDecoder>()
    private val service = GoogleOidcService(
        GoogleAuthProperties(clientId = "client-1", clientSecret = "secret"),
        decoder,
        RestClient.create(),
    )

    private fun jwt(claims: Map<String, Any>) = Jwt.withTokenValue("t")
        .header("alg", "RS256")
        .claims { it.putAll(claims) }
        .issuedAt(Instant.parse("2026-11-15T12:00:00Z"))
        .build()

    @Test
    fun `authorization url asks only for openid and email, with PKCE`() {
        val request = service.buildAuthorizationRequest("https://tasks.test/api/auth/google/callback")
        val url = URLDecoder.decode(request.authorizationUrl, StandardCharsets.UTF_8)

        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?client_id=client-1")
        assertThat(url).contains("scope=openid email&", "code_challenge_method=S256", "state=${request.state}")
        assertThat(url).doesNotContain(request.codeVerifier)
    }

    @Test
    fun `maps the id_token claims`() {
        whenever(decoder.decode("token")).thenReturn(
            jwt(mapOf("sub" to "g-123", "email" to "alice@acme.test", "email_verified" to true, "hd" to "acme.test")),
        )

        assertThat(service.verifyIdToken("token"))
            .isEqualTo(GoogleAuthData("g-123", "alice@acme.test", emailVerified = true, hostedDomain = "acme.test"))
    }

    @Test
    fun `treats a missing email_verified claim as unverified`() {
        whenever(decoder.decode("token")).thenReturn(jwt(mapOf("sub" to "g-123", "email" to "alice@gmail.com")))

        assertThat(service.verifyIdToken("token").emailVerified).isFalse()
    }

    @Test
    fun `wraps a rejected token`() {
        whenever(decoder.decode("token")).thenThrow(BadJwtException("expired"))

        assertThatThrownBy { service.verifyIdToken("token") }.isInstanceOf(GoogleAuthException::class.java)
    }
}
