package dev.itayp.tasker.service

import dev.itayp.tasker.config.InboundMailProperties
import dev.itayp.tasker.crypto.DataEncryptionProperties
import dev.itayp.tasker.crypto.InboundMailTokenHasher
import dev.itayp.tasker.crypto.UserCryptoService
import dev.itayp.tasker.jpa.InboundMailAddressEntity
import dev.itayp.tasker.repository.InboundMailAddressRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.mockito.junit.jupiter.MockitoSettings
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InboundMailAddressServiceTest {

    @Mock lateinit var repository: InboundMailAddressRepository
    @Mock lateinit var userCryptoService: UserCryptoService

    private val fixedNow = Instant.parse("2026-10-22T12:00:00Z")
    private val userId = UUID.randomUUID()
    private val hasher = InboundMailTokenHasher(DataEncryptionProperties(Base64.getEncoder().encodeToString(ByteArray(32))))

    private val service by lazy {
        InboundMailAddressService(
            repository,
            userCryptoService,
            hasher,
            InboundMailProperties(enabled = true, domain = "m.example.com"),
            Clock.fixed(fixedNow, ZoneOffset.UTC),
        )
    }

    @BeforeEach
    fun fakeEncryption() {
        // Reversible stand-in: enough to check what is stored and what comes back.
        whenever(userCryptoService.encrypt(eq(userId), any())).thenAnswer { "enc:${it.arguments[1]}".toByteArray() }
        whenever(userCryptoService.decrypt(eq(userId), any())).thenAnswer {
            (it.arguments[1] as ByteArray).toString(Charsets.UTF_8).removePrefix("enc:")
        }
        whenever(repository.save(any<InboundMailAddressEntity>())).thenAnswer { it.arguments[0] }
    }

    private fun stored(token: String) = InboundMailAddressEntity().apply {
        this.userId = this@InboundMailAddressServiceTest.userId
        tokenHmac = hasher.hash(token)
        this.token = "enc:$token".toByteArray()
        createdAt = fixedNow
    }

    @Test
    fun `creates a tasks-dot-token address and stores only the HMAC and the encrypted token`() {
        whenever(repository.findById(userId)).thenReturn(Optional.empty())

        val created = service.rotate(userId)

        assertThat(created.address).matches("tasks\\.[a-z2-7]{10}@m\\.example\\.com")
        assertThat(created.createdAt).isEqualTo(fixedNow)
        val token = created.address.substringAfter("tasks.").substringBefore("@")
        val saved = argumentCaptor<InboundMailAddressEntity>().apply { verify(repository).save(capture()) }.firstValue
        assertThat(saved.userId).isEqualTo(userId)
        assertThat(saved.tokenHmac).isEqualTo(hasher.hash(token))
        assertThat(saved.token!!.toString(Charsets.UTF_8)).isEqualTo("enc:$token")
    }

    @Test
    fun `rotating replaces the token in the existing row`() {
        val existing = stored("aaaaaaaaaa")
        whenever(repository.findById(userId)).thenReturn(Optional.of(existing))

        val rotated = service.rotate(userId)

        assertThat(rotated.address).doesNotContain("aaaaaaaaaa")
        assertThat(existing.tokenHmac).isNotEqualTo(hasher.hash("aaaaaaaaaa"))
    }

    @Test
    fun `shows the stored address again`() {
        whenever(repository.findById(userId)).thenReturn(Optional.of(stored("k7f3x5q2mz")))

        assertThat(service.getAddress(userId)?.address).isEqualTo("tasks.k7f3x5q2mz@m.example.com")
    }

    @Test
    fun `revoking deletes the row, and is a no-op without one`() {
        whenever(repository.existsById(userId)).thenReturn(true, false)

        assertThat(service.revoke(userId)).isTrue()
        assertThat(service.revoke(userId)).isFalse()
        verify(repository).deleteById(userId)
    }

    @Test
    fun `resolves a recipient by its token, ignoring case and the cosmetic prefix`() {
        whenever(repository.findByTokenHmac(hasher.hash("k7f3x5q2mz"))).thenReturn(stored("k7f3x5q2mz"))

        assertThat(service.resolveRecipient("tasks.k7f3x5q2mz@m.example.com")).isEqualTo(userId)
        assertThat(service.resolveRecipient("Tasks.K7F3X5Q2MZ@M.Example.com")).isEqualTo(userId)
        assertThat(service.resolveRecipient("joe.k7f3x5q2mz@m.example.com")).isEqualTo(userId)
    }

    @Test
    fun `does not resolve an unknown token, another domain, or a malformed address`() {
        whenever(repository.findByTokenHmac(any())).thenReturn(null)

        assertThat(service.resolveRecipient("tasks.abcdefgh23@m.example.com")).isNull()
        listOf(
            "tasks.k7f3x5q2mz@example.com",
            "k7f3x5q2mz@m.example.com",
            "tasks.k7f3x5q2m@m.example.com",
            "tasks.k7f3x5q2m1@m.example.com",
            "postmaster@m.example.com",
            "tasks.k7f3x5q2mz",
        ).forEach { assertThat(service.resolveRecipient(it)).`as`(it).isNull() }
        verify(repository, never()).findByTokenHmac(eq(hasher.hash("k7f3x5q2mz")))
    }
}
