package dev.itayp.tasker.channel.inboundmail

import dev.itayp.tasker.capture.SavedCapture
import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.email.EmailMessage
import dev.itayp.tasker.channel.email.EmailProperties
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import dev.itayp.tasker.config.AppProperties
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.planning.ScheduledConversationChannelResolver
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.UserSettingsService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.support.StaticMessageSource
import java.time.Instant
import java.util.Locale
import java.util.Optional
import java.util.UUID

/** The email path: Telegram is unreachable, so the confirmation goes to the account's verified address. */
class InboundMailConfirmationTest {

    private val userId = UUID.randomUUID()
    private val channelResolver: ScheduledConversationChannelResolver = mock()
    private val emailChannel: OutboundChannel = mock()
    private val templateEngine: EmailTemplateEngine = mock()
    private val userRepository: UserRepository = mock()
    private val userCryptoService: UserCryptoService = mock()
    private val userSettingsService: UserSettingsService = mock()
    private val messages = StaticMessageSource().apply {
        addMessage("inboundmail.saved.intro", Locale.ENGLISH, "From “{0}”, added:")
        addMessage("inboundmail.failed", Locale.ENGLISH, "“{0}” failed")
        addMessage("inboundmail.unverified", Locale.ENGLISH, "Unverified")
        addMessage("email.inboundmail.subject", Locale.ENGLISH, "Added from your email")
        addMessage("email.inboundmail.failed.subject", Locale.ENGLISH, "Your email wasn’t added")
    }

    private val confirmation = InboundMailConfirmation(
        channelResolver, emailChannel, templateEngine, EmailProperties(enabled = true), userRepository,
        userCryptoService, userSettingsService, messages, AppProperties(),
    )

    private val saved = SavedCapture(boardId = UUID.randomUUID(), titles = listOf("Pay the bill"), taskIds = emptyList(), invitesSkipped = false)

    @BeforeEach
    fun setUp() {
        whenever(userSettingsService.getLocale(userId)).thenReturn(Locale.ENGLISH)
        whenever(channelResolver.resolve(userId)).thenReturn(null)
        whenever(templateEngine.render(any(), any(), any())).thenReturn("<p>html</p>")
        val user = UserEntity().apply {
            id = userId
            email = byteArrayOf(1)
            emailVerifiedAt = Instant.parse("2026-01-01T00:00:00Z")
        }
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        whenever(userCryptoService.decrypt(any(), anyOrNull())).thenReturn("me@example.com")
    }

    private fun payload(verified: Boolean, messageId: String? = "<abc@mail.example>", subject: String = "Fwd: Electricity bill") =
        InboundMailPayload(
            subject = subject, fromAddress = "me@example.com", fromName = null, body = "Due Friday",
            attachmentCount = 0, verified = verified, messageId = messageId,
        )

    private fun sent(): EmailMessage = argumentCaptor<EmailMessage>().also { verify(emailChannel).send(it.capture()) }.firstValue

    @Test
    fun `a verified email gets a threaded reply under its own subject`() {
        confirmation.sendSaved(userId, payload(verified = true), saved, emptyList())

        val email = sent()
        assertThat(email.to).containsExactly("me@example.com")
        assertThat(email.inReplyTo).isEqualTo("<abc@mail.example>")
        assertThat(email.subject).isEqualTo("Re: Fwd: Electricity bill")
    }

    @Test
    fun `a subject that is already a reply is not prefixed again`() {
        confirmation.sendSaved(userId, payload(verified = true, subject = "RE: Electricity bill"), saved, emptyList())

        assertThat(sent().subject).isEqualTo("RE: Electricity bill")
    }

    @Test
    fun `an unverified email keeps the app's subject, but still carries the thread headers`() {
        confirmation.sendSaved(userId, payload(verified = false), saved, emptyList())

        val email = sent()
        assertThat(email.subject).isEqualTo("Added from your email")
        assertThat(email.inReplyTo).isEqualTo("<abc@mail.example>")
    }

    @Test
    fun `without a Message-ID nothing is threaded`() {
        confirmation.sendFailure(userId, payload(verified = true, messageId = null))

        val email = sent()
        assertThat(email.subject).isEqualTo("Your email wasn’t added")
        assertThat(email.inReplyTo).isNull()
    }
}
