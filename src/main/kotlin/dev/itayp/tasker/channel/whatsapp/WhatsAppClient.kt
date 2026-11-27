package dev.itayp.tasker.channel.whatsapp

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI

/** Who a reply goes to: by phone number when Meta sent one, else by business-scoped user id. */
data class WhatsAppRecipient(val phone: String?, val userId: String) {
    /** Never print either. */
    override fun toString(): String = "WhatsAppRecipient(***)"
}

/** One interactive option. [description] is shown under a list row's title; buttons have none. */
data class WhatsAppOption(val id: String, val title: String, val description: String? = null)

/**
 * The Graph API calls WhatsApp quick-add makes (`docs/MESSAGING-CHANNELS.md`, section 5), all from
 * our business number. Each call is one HTTP request; failures surface as
 * [WhatsAppApiException] with Meta's error code, so a caller can tell "the chat is closed" from
 * our own bug.
 */
class WhatsAppClient(
    private val properties: WhatsAppProperties,
    restClientBuilder: RestClient.Builder,
    private val objectMapper: ObjectMapper,
    baseUrl: String = "https://graph.facebook.com/$GRAPH_API_VERSION",
) {
    private val log = LoggerFactory.getLogger(WhatsAppClient::class.java)
    private val restClient = restClientBuilder
        .baseUrl(baseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.accessToken}")
        .build()

    @Volatile
    private var displayNumber: String? = null

    /** `preview_url` stays off, for the reason `TelegramConversationChannel.NO_LINK_PREVIEW` gives. */
    fun sendText(to: WhatsAppRecipient, body: String) =
        send(to, "text", mapOf("preview_url" to false, "body" to body))

    /** Up to three reply buttons under [body]. */
    fun sendButtons(to: WhatsAppRecipient, body: String, options: List<WhatsAppOption>) =
        send(
            to, "interactive",
            mapOf(
                "type" to "button",
                "body" to mapOf("text" to body),
                "action" to mapOf(
                    "buttons" to options.map { mapOf("type" to "reply", "reply" to mapOf("id" to it.id, "title" to it.title)) },
                ),
            ),
        )

    /** A list message: [button] opens up to ten rows. */
    fun sendList(to: WhatsAppRecipient, body: String, button: String, options: List<WhatsAppOption>) =
        send(
            to, "interactive",
            mapOf(
                "type" to "list",
                "body" to mapOf("text" to body),
                "action" to mapOf(
                    "button" to button,
                    "sections" to listOf(
                        mapOf(
                            "rows" to options.map { option ->
                                buildMap {
                                    put("id", option.id)
                                    put("title", option.title)
                                    option.description?.let { put("description", it) }
                                }
                            },
                        ),
                    ),
                ),
            ),
        )

    /**
     * Marks [messageId] read and shows "typing…" in the chat until we reply or 25 seconds pass.
     * Best effort: a failure only logs.
     */
    fun showTyping(messageId: String) {
        try {
            post(
                mapOf(
                    "messaging_product" to "whatsapp",
                    "status" to "read",
                    "message_id" to messageId,
                    "typing_indicator" to mapOf("type" to "text"),
                ),
            )
        } catch (e: WhatsAppApiException) {
            log.debug("WhatsApp typing indicator failed (code {})", e.code)
        }
    }

    /**
     * Our number in international digits (no `+`), for `wa.me` links; looked up once, then cached.
     * Null when the lookup fails, for whatever reason: linking still works with the code typed.
     */
    fun displayNumber(): String? {
        displayNumber?.let { return it }
        return try {
            getJson("/{id}?fields=display_phone_number", properties.phoneNumberId)
                .path("display_phone_number").asString("").filter(Char::isDigit).ifEmpty { null }
                ?.also { displayNumber = it }
        } catch (e: Exception) {
            log.warn("Could not look up the WhatsApp display number: {}", e.message)
            null
        }
    }

    /**
     * Where to download a media id from a webhook, and what Meta says it is. The URL is valid for
     * five minutes and needs the same bearer token.
     */
    fun mediaInfo(mediaId: String): WhatsAppMediaInfo {
        val node = try {
            getJson("/{id}", mediaId)
        } catch (e: RestClientResponseException) {
            throw WhatsAppApiException(e.statusCode.value(), errorCode(e), e)
        } catch (e: RestClientException) {
            throw WhatsAppApiException(0, null, e)
        } catch (e: JacksonException) {
            throw WhatsAppApiException(200, null, e)
        }
        val url = node.path("url").asString("")
        if (url.isEmpty()) throw WhatsAppApiException(200, null, IllegalStateException("No URL for media $mediaId"))
        return WhatsAppMediaInfo(
            url = url,
            mimeType = node.path("mime_type").asString("").ifEmpty { null },
            fileSize = node.path("file_size").asLong(-1).takeIf { it >= 0 },
        )
    }

    /** Reads at most [maxBytes] + 1 bytes from [url], so a caller can tell an oversized file apart. */
    fun download(url: String, maxBytes: Int): ByteArray =
        try {
            restClient.get().uri(URI.create(url)).exchange { _, response ->
                if (response.statusCode.isError) {
                    throw WhatsAppApiException(response.statusCode.value(), null, IllegalStateException("Media download failed"))
                }
                response.body.use { it.readNBytes(maxBytes + 1) }
            }
        } catch (e: RestClientResponseException) {
            throw WhatsAppApiException(e.statusCode.value(), null, e)
        } catch (e: ResourceAccessException) {
            throw WhatsAppApiException(0, null, e)
        }

    /**
     * A Graph API GET, parsed as JSON. Read as bytes first: Graph answers JSON as
     * `text/javascript`, which no message converter takes as JSON.
     */
    private fun getJson(uri: String, vararg variables: Any): JsonNode =
        objectMapper.readTree(restClient.get().uri(uri, *variables).retrieve().body(ByteArray::class.java) ?: ByteArray(0))

    private fun send(to: WhatsAppRecipient, type: String, content: Map<String, Any>) {
        val body = buildMap {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            // Meta prefers `to` when both are given; the user id is for senders who hide their number.
            if (to.phone != null) put("to", to.phone) else put("recipient", to.userId)
            put("type", type)
            put(type, content)
        }
        post(body)
    }

    private fun post(body: Map<String, Any>) {
        try {
            restClient.post()
                .uri("/{id}/messages", properties.phoneNumberId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .toBodilessEntity()
        } catch (e: RestClientResponseException) {
            throw WhatsAppApiException(e.statusCode.value(), errorCode(e), e)
        }
    }

    private fun errorCode(e: RestClientResponseException): Int? =
        runCatching { objectMapper.readTree(e.responseBodyAsByteArray).path("error").path("code").asInt(0) }
            .getOrNull()?.takeIf { it != 0 }

    companion object {
        /** The Graph API version every call uses. */
        const val GRAPH_API_VERSION = "v27.0"
    }
}

/** A media id's download location; [fileSize] in bytes, when Meta says. */
data class WhatsAppMediaInfo(val url: String, val mimeType: String?, val fileSize: Long?) {
    /** The URL is a short-lived credential of sorts: never print it. */
    override fun toString(): String = "WhatsAppMediaInfo(mimeType=$mimeType, fileSize=$fileSize)"
}

/** A Graph API call failed. [code] is Meta's `error.code`, when the response carried one. */
class WhatsAppApiException(val status: Int, val code: Int?, cause: Throwable) :
    RuntimeException("WhatsApp API call failed: HTTP $status, code $code", cause)
