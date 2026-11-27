package dev.itayp.tasker.channel.whatsapp

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper

class WhatsAppClientTest {

    private val properties = WhatsAppProperties(phoneNumberId = "106540352242922", accessToken = "token")
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = WhatsAppClient(properties, builder, JsonMapper.builder().build(), baseUrl = "https://graph.test/v27.0")

    private val messagesUrl = "https://graph.test/v27.0/106540352242922/messages"

    @Test
    fun `sends text to the phone number, with the bearer token and no link preview`() {
        server.expect(requestTo(messagesUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer token"))
            .andExpect(
                content().json(
                    """{ "messaging_product": "whatsapp", "recipient_type": "individual", "to": "16505551234",
                        "type": "text", "text": { "preview_url": false, "body": "Hi" } }""",
                    org.springframework.test.json.JsonCompareMode.STRICT,
                ),
            )
            .andRespond(withSuccess("""{"messages":[{"id":"wamid.out"}]}""", MediaType.APPLICATION_JSON))

        client.sendText(WhatsAppRecipient("16505551234", "US.1349"), "Hi")

        server.verify()
    }

    @Test
    fun `addresses a sender without a phone number by business-scoped id`() {
        server.expect(requestTo(messagesUrl))
            .andExpect(content().json("""{ "recipient": "US.1349", "type": "interactive" }"""))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\"to\""))))
            .andRespond(withSuccess())

        client.sendButtons(WhatsAppRecipient(null, "US.1349"), "Save?", listOf(WhatsAppOption("save", "Save")))

        server.verify()
    }

    @Test
    fun `sends a list with one section of rows`() {
        server.expect(requestTo(messagesUrl))
            .andExpect(
                content().json(
                    """{ "type": "interactive", "interactive": { "type": "list", "body": { "text": "Pick" },
                        "action": { "button": "Choose", "sections": [{ "rows": [
                          { "id": "a", "title": "A" }, { "id": "b", "title": "B…", "description": "B long" } ] }] } } }""",
                ),
            )
            .andRespond(withSuccess())

        client.sendList(
            WhatsAppRecipient("1", "US.1"), "Pick", "Choose",
            listOf(WhatsAppOption("a", "A"), WhatsAppOption("b", "B…", "B long")),
        )

        server.verify()
    }

    @Test
    fun `a failed call carries Meta's error code`() {
        server.expect(requestTo(messagesUrl)).andRespond(
            withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("""{"error":{"message":"Re-engagement message","code":131047}}"""),
        )

        assertThatThrownBy { client.sendText(WhatsAppRecipient("1", "US.1"), "Hi") }
            .isInstanceOfSatisfying(WhatsAppApiException::class.java) { assertThat(it.code).isEqualTo(131047) }
    }

    @Test
    fun `the typing indicator marks the message read, and a failure is swallowed`() {
        server.expect(requestTo(messagesUrl))
            .andExpect(
                content().json(
                    """{ "messaging_product": "whatsapp", "status": "read", "message_id": "wamid.in",
                        "typing_indicator": { "type": "text" } }""",
                ),
            )
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        client.showTyping("wamid.in")

        server.verify()
    }

    @Test
    fun `the display number is looked up once, as digits`() {
        server.expect(requestTo("https://graph.test/v27.0/106540352242922?fields=display_phone_number"))
            .andRespond(withSuccess("""{"display_phone_number":"+1 555-078-3881","id":"1"}""", MediaType.APPLICATION_JSON))

        assertThat(client.displayNumber()).isEqualTo("15550783881")
        assertThat(client.displayNumber()).isEqualTo("15550783881")

        server.verify()
    }

    @Test
    fun `looks up a media id, then downloads it with the token, capped`() {
        server.expect(requestTo("https://graph.test/v27.0/img1"))
            .andRespond(
                withSuccess(
                    """{"url":"https://lookaside.test/img1?x=1","mime_type":"image/jpeg","file_size":5,"id":"img1"}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        server.expect(requestTo("https://lookaside.test/img1?x=1"))
            .andExpect(header("Authorization", "Bearer token"))
            .andRespond(withSuccess(byteArrayOf(1, 2, 3, 4, 5), MediaType.IMAGE_JPEG))

        val info = client.mediaInfo("img1")
        assertThat(info).isEqualTo(WhatsAppMediaInfo("https://lookaside.test/img1?x=1", "image/jpeg", 5))
        assertThat(client.download(info.url, 3)).hasSize(4)

        server.verify()
    }

    @Test
    fun `a failed media download is an API exception`() {
        server.expect(requestTo("https://lookaside.test/gone")).andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertThatThrownBy { client.download("https://lookaside.test/gone", 10) }
            .isInstanceOfSatisfying(WhatsAppApiException::class.java) { assertThat(it.status).isEqualTo(404) }
    }
}
