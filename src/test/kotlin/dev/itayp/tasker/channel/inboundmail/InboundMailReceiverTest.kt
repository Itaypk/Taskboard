package dev.itayp.tasker.channel.inboundmail

import dev.itayp.tasker.channel.InboundAttachment
import dev.itayp.tasker.config.InboundMailProperties
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.InboundMailEntity
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.ratelimit.RateLimiter
import dev.itayp.tasker.repository.InboundMailRepository
import dev.itayp.tasker.repository.UserRepository
import dev.itayp.tasker.service.EmailHasher
import dev.itayp.tasker.service.InboundMailAddressService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InboundMailReceiverTest {

    @Mock lateinit var addressService: InboundMailAddressService
    @Mock lateinit var repository: InboundMailRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var userCryptoService: UserCryptoService
    @Mock lateinit var rateLimiter: RateLimiter

    private val userId = UUID.randomUUID()
    private val to = "tasks.k7f3x5q2mz@m.example.com"
    private val objectMapper = jacksonObjectMapper()
    private val meterRegistry = SimpleMeterRegistry()
    private val properties = InboundMailProperties(enabled = true, domain = "m.example.com", deliveryToken = "x".repeat(32))

    private val receiver by lazy {
        InboundMailReceiver(
            addressService, InboundMailParser(properties), repository, userRepository, userCryptoService,
            objectMapper, rateLimiter, properties, meterRegistry,
            Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC),
        )
    }

    @BeforeEach
    fun setUp() {
        whenever(addressService.resolveRecipient(to)).thenReturn(userId)
        whenever(rateLimiter.tryConsume(userId.toString())).thenReturn(true)
        whenever(userCryptoService.encrypt(eq(userId), any())).thenAnswer { (it.arguments[1] as String).toByteArray() }
        whenever(repository.saveAndFlush(any<InboundMailEntity>())).thenAnswer { it.arguments[0] }
        whenever(userRepository.findById(userId)).thenReturn(
            Optional.of(UserEntity().apply {
                emailHash = EmailHasher.hash("me@example.org")
                emailVerifiedAt = Instant.parse("2026-01-01T00:00:00Z")
            }),
        )
    }

    private fun message(from: String = "me@example.org", extraHeaders: String = "") =
        ("${extraHeaders}From: $from\r\nMessage-ID: <1@example.org>\r\nSubject: Call the plumber\r\n\r\nThe sink leaks.\r\n").toByteArray()

    private fun ours(dmarc: String) = "Authentication-Results: m.example.com; dmarc=$dmarc header.from=example.org\r\n"

    private fun queuedPayload(): InboundMailPayload {
        val saved = argumentCaptor<InboundMailEntity>().apply { verify(repository).saveAndFlush(capture()) }.firstValue
        assertThat(saved.userId).isEqualTo(userId)
        return objectMapper.readValue(saved.payload!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `queues a manual forward from the account's own address as verified`() {
        assertThat(receiver.receive("me@example.org", to, message(extraHeaders = ours("pass")))).isEqualTo(ReceiveOutcome.QUEUED)

        val payload = queuedPayload()
        assertThat(payload.verified).isTrue()
        assertThat(payload.subject).isEqualTo("Call the plumber")
        assertThat(payload.body).isEqualTo("The sink leaks.")
        assertThat(meterRegistry.counter("tasker.inbound_mail.received", "outcome", "queued").count()).isEqualTo(1.0)
    }

    @Test
    fun `queues as unverified without our DMARC pass, or from another address`() {
        receiver.receive("me@example.org", to, message(extraHeaders = ours("fail")))
        assertThat(queuedPayload().verified).isFalse()
    }

    @Test
    fun `a DMARC pass for someone else's address is still unverified`() {
        val stranger = "Authentication-Results: m.example.com; dmarc=pass header.from=other.example\r\n"
        receiver.receive("x@other.example", to, message(from = "x@other.example", extraHeaders = stranger))

        assertThat(queuedPayload().verified).isFalse()
    }

    @Test
    fun `drops postmaster, unknown addresses, bounces and auto-replies without queueing`() {
        assertThat(receiver.receive("a@b.example", "postmaster@m.example.com", message())).isEqualTo(ReceiveOutcome.POSTMASTER)
        assertThat(receiver.receive("a@b.example", "tasks.aaaaaaaaaa@m.example.com", message()))
            .isEqualTo(ReceiveOutcome.UNKNOWN_RECIPIENT)
        assertThat(receiver.receive("", to, message())).isEqualTo(ReceiveOutcome.BOUNCE)
        assertThat(receiver.receive("a@b.example", to, message(extraHeaders = "Auto-Submitted: auto-replied\r\n")))
            .isEqualTo(ReceiveOutcome.AUTO_SUBMITTED)

        verify(repository, never()).saveAndFlush(any<InboundMailEntity>())
    }

    @Test
    fun `ignores a retried delivery before it costs any rate-limit budget`() {
        whenever(repository.existsByDedupKey(any())).thenReturn(true)

        assertThat(receiver.receive("me@example.org", to, message())).isEqualTo(ReceiveOutcome.DUPLICATE)
        verify(rateLimiter, never()).tryConsume(any())
    }

    @Test
    fun `drops mail over the rate limit`() {
        whenever(rateLimiter.tryConsume(userId.toString())).thenReturn(false)

        assertThat(receiver.receive("me@example.org", to, message())).isEqualTo(ReceiveOutcome.RATE_LIMITED)
        verify(repository, never()).saveAndFlush(any<InboundMailEntity>())
    }

    @Test
    fun `stores the readable attachments apart from the payload, which names the unread ones`() {
        val png = java.util.Base64.getMimeEncoder().encodeToString(ByteArray(20_000) { 1 })
        val raw = (
            "From: me@example.org\r\nMessage-ID: <2@example.org>\r\nSubject: Flyer\r\n" +
                "Content-Type: multipart/mixed; boundary=\"m\"\r\n\r\n" +
                "--m\r\nContent-Type: text/plain\r\n\r\nSee attached.\r\n" +
                "--m\r\nContent-Type: image/png\r\nContent-Disposition: attachment; filename=\"flyer.png\"\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\n$png\r\n" +
                "--m\r\nContent-Type: application/msword\r\nContent-Disposition: attachment; filename=\"notes.doc\"\r\n\r\nx\r\n" +
                "--m--\r\n"
            ).toByteArray()

        assertThat(receiver.receive("me@example.org", to, raw)).isEqualTo(ReceiveOutcome.QUEUED)

        val saved = argumentCaptor<InboundMailEntity>().apply { verify(repository).saveAndFlush(capture()) }.firstValue
        val payload: InboundMailPayload = objectMapper.readValue(saved.payload!!.toString(Charsets.UTF_8))
        assertThat(payload.attachmentCount).isEqualTo(2)
        assertThat(payload.unreadAttachments).containsExactly("notes.doc")
        val attachments: List<InboundAttachment> = objectMapper.readValue(saved.attachments!!.toString(Charsets.UTF_8))
        assertThat(attachments.single().filename).isEqualTo("flyer.png")
        assertThat(attachments.single().bytes).hasSize(20_000)
    }

    @Test
    fun `stores no attachments column for a text-only message`() {
        receiver.receive("me@example.org", to, message())

        val saved = argumentCaptor<InboundMailEntity>().apply { verify(repository).saveAndFlush(capture()) }.firstValue
        assertThat(saved.attachments).isNull()
    }
}
