package dev.itayp.tasker.channel.whatsapp

import dev.itayp.tasker.channel.ChannelMessage
import dev.itayp.tasker.channel.ChoiceOption
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration

class WhatsAppConversationChannelTest {

    private val client: WhatsAppClient = mock()
    private val recipient = WhatsAppRecipient("16505551234", "US.1349")
    private val meterRegistry = SimpleMeterRegistry()
    private val channel = WhatsAppConversationChannel(
        client, recipient, "wamid.in", { key -> "[$key]" }, meterRegistry, typingRefresh = Duration.ofMillis(10),
    )

    private fun options(vararg labels: String) = labels.mapIndexed { i, label -> ChoiceOption("o$i", label) }

    private fun outbound(outcome: String) =
        meterRegistry.find(WhatsAppConversationChannel.OUTBOUND_METRIC).tags("outcome", outcome).counter()?.count() ?: 0.0

    @Test
    fun `a short choice with short labels becomes reply buttons`() {
        channel.send(ChannelMessage.Choice("Buy milk?", options("✅ Save", "✏️ Adjust", "✖️ Cancel")))

        verify(client).sendButtons(
            recipient, "Buy milk?",
            listOf(WhatsAppOption("o0", "✅ Save"), WhatsAppOption("o1", "✏️ Adjust"), WhatsAppOption("o2", "✖️ Cancel")),
        )
        assertThat(outbound("sent")).isEqualTo(1.0)
    }

    @Test
    fun `more than three options, or a long label, become a list with cut titles`() {
        val long = "Wednesday, 2 December 2026"
        channel.send(ChannelMessage.Choice("Pick a day:", options("Monday", long, "Thursday", "✖️ Not now")))

        val rows = argumentCaptor<List<WhatsAppOption>>()
        verify(client).sendList(eq(recipient), eq("Pick a day:"), eq("[messaging.choose]"), rows.capture())
        assertThat(rows.firstValue[0]).isEqualTo(WhatsAppOption("o0", "Monday"))
        assertThat(rows.firstValue[1].title).hasSize(24).endsWith("…")
        assertThat(rows.firstValue[1].description).isEqualTo(long)
        verify(client, never()).sendButtons(any(), any(), any())
    }

    @Test
    fun `more than ten options become a numbered text list`() {
        channel.send(ChannelMessage.Choice("Which one?", options(*Array(11) { "Option ${it + 1}" })))

        val text = argumentCaptor<String>()
        verify(client).sendText(eq(recipient), text.capture())
        assertThat(text.firstValue).startsWith("Which one?\n\n1. Option 1\n2. Option 2").endsWith("11. Option 11")
    }

    @Test
    fun `a prompt too long for the buttons goes first, with the buttons under a short line`() {
        val prompt = "x".repeat(WhatsAppConversationChannel.BUTTON_BODY_LIMIT + 1)
        channel.send(ChannelMessage.Choice(prompt, options("Save", "Cancel")))

        inOrder(client) {
            verify(client).sendText(recipient, prompt)
            verify(client).sendButtons(recipient, "[messaging.choose]", listOf(WhatsAppOption("o0", "Save"), WhatsAppOption("o1", "Cancel")))
        }
    }

    @Test
    fun `long text is split on paragraph breaks`() {
        val first = "a".repeat(3000)
        val second = "b".repeat(3000)
        channel.send(ChannelMessage.Text("$first\n\n$second"))

        inOrder(client) {
            verify(client).sendText(recipient, first)
            verify(client).sendText(recipient, second)
        }
    }

    @Test
    fun `splitting never loses text, even without a break to split on`() {
        val parts = WhatsAppConversationChannel.splitText("z".repeat(10_000), 4096)

        assertThat(parts.map { it.length }).containsExactly(4096, 4096, 1808)
    }

    @Test
    fun `cutting never splits an emoji`() {
        assertThat(WhatsAppConversationChannel.cut("abc😀def", 5)).isEqualTo("abc…")
    }

    @Test
    fun `a closed chat is logged and dropped, any other failure is thrown`() {
        doThrow(WhatsAppApiException(400, 131047, RuntimeException())).whenever(client).sendText(any(), eq("closed"))
        doThrow(WhatsAppApiException(400, 100, RuntimeException())).whenever(client).sendText(any(), eq("bug"))

        channel.send(ChannelMessage.Text("closed"))
        assertThatThrownBy { channel.send(ChannelMessage.Text("bug")) }.isInstanceOf(WhatsAppApiException::class.java)

        assertThat(outbound("undeliverable")).isEqualTo(1.0)
        assertThat(outbound("failed")).isEqualTo(1.0)
    }

    @Test
    fun `typing is shown for the inbound message while work runs, and stops after`() {
        val result = channel.whileWorking {
            Thread.sleep(50)
            "done"
        }

        assertThat(result).isEqualTo("done")
        verify(client, atLeast(2)).showTyping("wamid.in")
    }
}
