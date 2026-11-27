package dev.itayp.tasker.channel.messaging

import dev.itayp.tasker.ai.access.AiAccessService
import dev.itayp.tasker.capture.CaptureEntry
import dev.itayp.tasker.capture.QuickAddFlow
import dev.itayp.tasker.capture.QuickAddState
import dev.itayp.tasker.channel.ChannelCapabilities
import dev.itayp.tasker.channel.ChannelInbound
import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChannelType
import dev.itayp.tasker.channel.ConversationChannel
import dev.itayp.tasker.channel.MessageFormatter
import dev.itayp.tasker.config.RateLimitProperties
import dev.itayp.tasker.planning.CaptureIntent
import dev.itayp.tasker.service.UserSettingsService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.MessageSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

class MessagingCaptureServiceTest {

    private val now = Instant.parse("2026-11-26T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val userId = UUID.randomUUID()
    private val sender = "IL.1234567890"

    private val linkService: ChannelLinkService = mock()
    private val quickAddFlow: QuickAddFlow = mock()
    private val aiAccessService: AiAccessService = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val messageSource: MessageSource = mock()
    private val meterRegistry = SimpleMeterRegistry()
    private val registry = MessagingCaptureRegistry(meterRegistry, clock)
    private val service = MessagingCaptureService(
        linkService, registry, quickAddFlow, aiAccessService, userSettingsService, messageSource, meterRegistry,
        clock, RateLimitProperties(),
    )

    private val sent = mutableListOf<String>()
    private val reply = object : ConversationChannel {
        override val type = ChannelType.WHATSAPP
        override val capabilities = ChannelCapabilities(supportsAutocompletions = false, supportsInlineButtons = true)
        override val formatter: MessageFormatter = mock()
        override fun send(message: ChannelMessage) {
            sent += (message as ChannelMessage.Text).text
        }
    }

    private val key = ConversationKey(ChannelType.WHATSAPP, sender)

    @BeforeEach
    fun setUp() {
        whenever(messageSource.getMessage(any<String>(), anyOrNull(), any<Locale>())).thenAnswer {
            "${it.arguments[0]}@${it.arguments[2]}"
        }
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.forLanguageTag("he"))
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(true)
    }

    private fun event(content: InboundEvent.Content, id: String = "wamid.1") =
        InboundEvent(ChannelType.WHATSAPP, id, sender, "972500000000", content, reply)

    private fun text(text: String, id: String = "wamid.1") = event(InboundEvent.Content.Text(text), id)

    private fun linked() = whenever(linkService.resolve(ChannelType.WHATSAPP, sender)).thenReturn(userId)

    private fun inbound(outcome: String) =
        meterRegistry.find(MessagingCaptureService.INBOUND_METRIC).tags("channel", "whatsapp", "outcome", outcome).counter()?.count() ?: 0.0

    @Test
    fun `a link code is consumed and confirmed in the linked user's language`() {
        whenever(linkService.consume(ChannelType.WHATSAPP, sender, "972500000000", "ABCDEFG2"))
            .thenReturn(LinkOutcome.Linked(userId, alreadyLinked = false))

        service.process(text("Link my account: ABCD-EFG2"))

        assertThat(sent).containsExactly("messaging.linked@he")
        verify(quickAddFlow, never()).beginUnprompted(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `a bad code gets a reply, a rate-limited one gets silence`() {
        whenever(linkService.consume(any(), any(), anyOrNull(), any())).thenReturn(LinkOutcome.Invalid, LinkOutcome.RateLimited)

        service.process(text("ABCD-EFG2"))
        service.process(text("ABCD-EFG2", id = "wamid.2"))

        assertThat(sent).containsExactly("messaging.link.invalid@en")
    }

    @Test
    fun `a code for another account is refused in the language of the account the chat is linked to`() {
        whenever(linkService.consume(any(), any(), anyOrNull(), any())).thenReturn(LinkOutcome.Conflict(userId))

        service.process(text("ABCD-EFG2"))

        assertThat(sent).containsExactly("messaging.link.conflict@he")
    }

    @Test
    fun `an unlinked sender is told how to connect once, in English, then ignored`() {
        service.process(text("buy milk"))
        service.process(text("buy eggs", id = "wamid.2"))

        assertThat(sent).containsExactly("messaging.unlinked@en")
        assertThat(inbound("unlinked")).isEqualTo(2.0)
        verify(quickAddFlow, never()).beginUnprompted(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `nothing is captured while AI is off for the user`() {
        linked()
        whenever(aiAccessService.isAiAvailableForUser(userId)).thenReturn(false)

        service.process(text("buy milk"))

        assertThat(sent).containsExactly("messaging.ai_disabled@he")
        verify(quickAddFlow, never()).beginUnprompted(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `free text opens a capture and its state is kept for the next message`() {
        linked()
        val draft = QuickAddState.AwaitingDescription(now)
        whenever(quickAddFlow.beginUnprompted(userId, reply, "buy milk")).thenReturn(CaptureEntry.Captured(draft))

        service.process(text("buy milk"))

        assertThat(registry.get(key)).isEqualTo(draft)
        assertThat(inbound("captured")).isEqualTo(1.0)
    }

    @Test
    fun `a message during a capture continues it, and a finished capture clears the state`() {
        linked()
        val draft = QuickAddState.AwaitingDescription(now)
        registry.set(key, draft)
        whenever(quickAddFlow.handleInbound(eq(userId), eq(reply), eq(draft), eq(ChannelInbound.Selection("save")), anyOrNull()))
            .thenReturn(CaptureEntry.Captured(null))

        service.process(event(InboundEvent.Content.Selection("save")))

        assertThat(registry.get(key)).isNull()
    }

    @Test
    fun `a tap on a card with no capture behind it says the card expired`() {
        linked()

        service.process(event(InboundEvent.Content.Selection("save")))

        assertThat(sent).containsExactly("messaging.card_expired@he")
    }

    @Test
    fun `a message we can't read is declined without touching a capture in progress`() {
        linked()
        val state = QuickAddState.AwaitingDescription(now)
        registry.set(key, state)

        service.process(event(InboundEvent.Content.Unsupported("sticker")))

        assertThat(sent).containsExactly("quickadd.media.unsupported_type@he")
        assertThat(registry.get(key)).isSameAs(state)
        assertThat(inbound("unsupported")).isEqualTo(1.0)
    }

    @Test
    fun `a message that isn't a capture gets the channel's capabilities, not a bot command`() {
        linked()
        whenever(quickAddFlow.beginUnprompted(userId, reply, "plan my week"))
            .thenReturn(CaptureEntry.Routed(CaptureIntent.PLAN, "plan my week"))

        service.process(text("plan my week"))

        assertThat(sent).containsExactly("messaging.capabilities@he")
        assertThat(registry.get(key)).isNull()
    }

    @Test
    fun `a failure replies, clears the capture and is counted`() {
        linked()
        registry.set(key, QuickAddState.AwaitingDescription(now))
        whenever(quickAddFlow.handleInbound(any(), any(), any(), any(), anyOrNull())).thenThrow(IllegalStateException("boom"))

        service.process(text("buy milk"))

        assertThat(sent).containsExactly("command.handleUpdateFailed@he")
        assertThat(registry.get(key)).isNull()
        assertThat(inbound("failed")).isEqualTo(1.0)
    }

    @Test
    fun `a redelivered event is dropped before it is processed`() {
        service.accept(text("buy milk"))
        service.accept(text("buy milk"))

        assertThat(inbound("duplicate")).isEqualTo(1.0)
    }
}
