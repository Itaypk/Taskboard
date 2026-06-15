package dev.itayp.tasker.service

import dev.itayp.tasker.config.TelegramAuthProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Instant

class TelegramOidcServiceTest {

    private val props = TelegramAuthProperties(
        clientId = "123456789",
        clientSecret = "s3cr3t",
        authorizationUri = "https://oauth.example/auth",
        tokenUri = "https://oauth.example/token",
    )
    private val decoder: JwtDecoder = mock()

    private fun newService(restClient: RestClient = RestClient.create()) =
        TelegramOidcService(props, decoder, restClient)

    private fun jwt(builder: Jwt.Builder.() -> Unit): Jwt =
        Jwt.withTokenValue("token").header("alg", "RS256").apply(builder).build()

    @Test
    fun `isConfigured requires client id and secret`() {
        assertThat(newService().isConfigured()).isTrue()
        assertThat(TelegramOidcService(props.copy(clientSecret = ""), decoder, RestClient.create()).isConfigured()).isFalse()
    }

    @Test
    fun `buildAuthorizationRequest produces a PKCE authorize url with fresh state`() {
        val service = newService()
        val req1 = service.buildAuthorizationRequest("https://app.example/cb")
        val req2 = service.buildAuthorizationRequest("https://app.example/cb")

        assertThat(req1.authorizationUrl)
            .startsWith("https://oauth.example/auth?")
            .contains("client_id=123456789")
            .contains("redirect_uri=https%3A%2F%2Fapp.example%2Fcb")
            .contains("response_type=code")
            .contains("code_challenge_method=S256")
            .contains("code_challenge=")
            .contains("state=")
        assertThat(req1.state).isNotBlank()
        assertThat(req1.codeVerifier).isNotBlank()
        // Each request is unguessable and unique.
        assertThat(req1.state).isNotEqualTo(req2.state)
        assertThat(req1.codeVerifier).isNotEqualTo(req2.codeVerifier)
    }

    @Test
    fun `verifyIdToken maps telegram claims`() {
        whenever(decoder.decode("good")).thenReturn(jwt {
            claim("id", 987654321L)
            claim("preferred_username", "alice")
            claim("name", "Alice")
            claim("picture", "https://cdn/p.png")
            issuedAt(Instant.parse("2026-04-21T12:00:00Z"))
        })

        val data = newService().verifyIdToken("good")

        assertThat(data.telegramId).isEqualTo(987654321L)
        assertThat(data.username).isEqualTo("alice")
        assertThat(data.firstName).isEqualTo("Alice")
        assertThat(data.photoUrl).isEqualTo("https://cdn/p.png")
        assertThat(data.authDate).isEqualTo(Instant.parse("2026-04-21T12:00:00Z"))
    }

    @Test
    fun `verifyIdToken accepts a string id claim`() {
        whenever(decoder.decode("strid")).thenReturn(jwt { claim("id", "555") })
        assertThat(newService().verifyIdToken("strid").telegramId).isEqualTo(555L)
    }

    @Test
    fun `verifyIdToken rejects a token missing the id claim`() {
        whenever(decoder.decode("noid")).thenReturn(jwt { claim("name", "Nobody") })
        assertThatThrownBy { newService().verifyIdToken("noid") }
            .isInstanceOf(TelegramAuthException::class.java)
            .hasMessageContaining("id")
    }

    @Test
    fun `verifyIdToken wraps decoder failures`() {
        whenever(decoder.decode("bad")).thenThrow(JwtException("bad signature"))
        assertThatThrownBy { newService().verifyIdToken("bad") }
            .isInstanceOf(TelegramAuthException::class.java)
            .hasMessageContaining("Invalid id_token")
    }

    @Test
    fun `completeAuthorization exchanges the code then validates the returned id_token`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://oauth.example/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"id_token":"the-jwt"}""", MediaType.APPLICATION_JSON))
        whenever(decoder.decode("the-jwt")).thenReturn(jwt { claim("id", 42L) })

        val data = newService(builder.build())
            .completeAuthorization("auth-code", "verifier", "https://app.example/cb")

        assertThat(data.telegramId).isEqualTo(42L)
        server.verify()
    }

    @Test
    fun `completeAuthorization fails when the token response has no id_token`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://oauth.example/token"))
            .andRespond(withSuccess("""{"access_token":"x"}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy {
            newService(builder.build()).completeAuthorization("c", "v", "https://app.example/cb")
        }.isInstanceOf(TelegramAuthException::class.java).hasMessageContaining("id_token")
    }

    @Test
    fun `completeAuthorization wraps token endpoint errors`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://oauth.example/token")).andRespond(withServerError())

        assertThatThrownBy {
            newService(builder.build()).completeAuthorization("c", "v", "https://app.example/cb")
        }.isInstanceOf(TelegramAuthException::class.java)
    }
}
