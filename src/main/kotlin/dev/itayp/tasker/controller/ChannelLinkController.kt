package dev.itayp.tasker.controller

import dev.itayp.tasker.channel.ChannelType
import dev.itayp.tasker.channel.messaging.ChannelLinkNotAllowedException
import dev.itayp.tasker.channel.messaging.ChannelLinkService
import dev.itayp.tasker.channel.messaging.MessagingProvider
import dev.itayp.tasker.security.TaskerPrincipal
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * One messaging channel as Settings shows it. [offered] is false for a channel the user linked
 * that this instance no longer runs, so it can still be disconnected; [link] is null when not
 * linked. [display] is already masked.
 */
data class ChannelLinkResponse(
    val channel: String,
    val offered: Boolean,
    val link: LinkedAccountResponse?,
)

data class LinkedAccountResponse(val display: String?, val linkedAt: String)

data class ChannelLinksResponse(val channels: List<ChannelLinkResponse>)

/** [url] opens the chat with the bot with [code] prefilled, when the platform allows it. */
data class LinkCodeResponse(val code: String, val expiresAt: String, val url: String?)

/**
 * The signed-in user's messaging-channel links (`docs/MESSAGING-CHANNELS.md`, section 3). On the
 * session chain with CSRF: only the user, in the app, can link or unlink. Which channels exist
 * comes from here, not the public config: it matters only to a signed-in user.
 */
@RestController
@RequestMapping("/api/v1/channel-links")
class ChannelLinkController(
    private val linkService: ChannelLinkService,
    providerBeans: ObjectProvider<MessagingProvider>,
) {
    // ObjectProvider rather than List: an instance with no channel configured has no provider beans.
    private val providers: List<MessagingProvider> = providerBeans.orderedStream().toList()

    @GetMapping
    fun list(@AuthenticationPrincipal principal: TaskerPrincipal): ChannelLinksResponse {
        val links = linkService.list(principal.userId).associateBy { it.channel }
        val channels = (providers.map { it.channel } + links.keys).distinct().sortedBy { it.metricTag }
        return ChannelLinksResponse(
            channels.map { channel ->
                ChannelLinkResponse(
                    channel = channel.metricTag,
                    offered = providers.any { it.channel == channel },
                    link = links[channel]?.let { LinkedAccountResponse(it.display, it.linkedAt.toString()) },
                )
            },
        )
    }

    /** Issues a link code, replacing any earlier one. */
    @PostMapping("/{channel}/token")
    fun startLink(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable channel: String,
    ): ResponseEntity<*> {
        val provider = providers.firstOrNull { it.channel.metricTag == channel } ?: return notOffered()
        val code = try {
            linkService.startLink(principal.userId, provider.channel)
        } catch (_: ChannelLinkNotAllowedException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ProblemDetail.forStatusAndDetail(
                    HttpStatus.FORBIDDEN,
                    "Save your account with a sign-in method before connecting a messaging app.",
                ),
            )
        }
        return ResponseEntity.ok(
            LinkCodeResponse(code.code, code.expiresAt.toString(), provider.linkInstructions(code.code).url),
        )
    }

    /** Disconnects the chat account. Idempotent: 204 whether or not one was linked. */
    @DeleteMapping("/{channel}")
    fun unlink(
        @AuthenticationPrincipal principal: TaskerPrincipal,
        @PathVariable channel: String,
    ): ResponseEntity<*> {
        val type = ChannelType.entries.firstOrNull { it.metricTag == channel } ?: return notOffered()
        linkService.unlink(principal.userId, type)
        return ResponseEntity.noContent().build<Void>()
    }

    private fun notOffered(): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "This messaging channel is not available on this instance."),
        )
}
