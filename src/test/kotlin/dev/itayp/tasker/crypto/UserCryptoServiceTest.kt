package dev.itayp.tasker.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Optional
import java.util.UUID
import javax.crypto.AEADBadTagException

@ExtendWith(MockitoExtension::class)
class UserCryptoServiceTest {

    @Mock
    private lateinit var repository: UserDataKeyRepository

    private lateinit var service: UserCryptoService

    private val properties = DataEncryptionProperties(
        kek = Base64.getEncoder().encodeToString(ByteArray(32) { 0x11 })
    )
    private val clock = Clock.fixed(Instant.parse("2026-05-24T12:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        service = UserCryptoService(repository, properties, clock)
    }

    @Test
    fun `ensureUserKey stores a wrapped DEK that the service can later use`() {
        val userId = UUID.randomUUID()
        whenever(repository.existsById(userId)).thenReturn(false)
        whenever(repository.save(any<UserDataKeyEntity>())).thenAnswer { it.arguments[0] as UserDataKeyEntity }

        service.ensureUserKey(userId)

        // Cached DEK lets encrypt+decrypt round-trip without another DB load
        val ciphertext = service.encrypt(userId, "secret note")
        assertNotNull(ciphertext)
        assertEquals("secret note", service.decrypt(userId, ciphertext))
        verify(repository).save(any<UserDataKeyEntity>())
    }

    @Test
    fun `ensureUserKey is a no-op when a row already exists`() {
        val userId = UUID.randomUUID()
        whenever(repository.existsById(userId)).thenReturn(true)

        service.ensureUserKey(userId)

        verify(repository, org.mockito.kotlin.never()).save(any<UserDataKeyEntity>())
    }

    @Test
    fun `encrypt and decrypt null returns null`() {
        val userId = UUID.randomUUID()
        // Even with no DEK row, null short-circuits before any lookup
        assertNull(service.encrypt(userId, null))
        assertNull(service.decrypt(userId, null))
    }

    @Test
    fun `decrypting another users ciphertext fails (AAD bind)`() {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        whenever(repository.existsById(any())).thenReturn(false)
        whenever(repository.save(any<UserDataKeyEntity>())).thenAnswer { it.arguments[0] as UserDataKeyEntity }
        service.ensureUserKey(userA)
        service.ensureUserKey(userB)

        val ciphertextA = service.encrypt(userA, "for A's eyes only")!!

        // userB has a different DEK; the unwrap+open against userB's key+AAD must fail
        assertThrows(AEADBadTagException::class.java) {
            service.decrypt(userB, ciphertextA)
        }
    }

    @Test
    fun `encrypt fails when the user has no DEK`() {
        val userId = UUID.randomUUID()
        whenever(repository.findById(userId)).thenReturn(Optional.empty())
        assertThrows(IllegalStateException::class.java) { service.encrypt(userId, "plaintext") }
    }

    @Test
    fun `encrypt produces fresh ciphertext each call`() {
        val userId = UUID.randomUUID()
        whenever(repository.existsById(userId)).thenReturn(false)
        whenever(repository.save(any<UserDataKeyEntity>())).thenAnswer { it.arguments[0] as UserDataKeyEntity }
        service.ensureUserKey(userId)

        val a = service.encrypt(userId, "same text")!!
        val b = service.encrypt(userId, "same text")!!
        assertNotEquals(a.toList(), b.toList())
        assertEquals(service.decrypt(userId, a), service.decrypt(userId, b))
    }

    @Test
    fun `malformed KEK fails fast`() {
        val bad = DataEncryptionProperties(kek = "not-base-64-!!!")
        val svc = UserCryptoService(repository, bad, clock)
        val userId = UUID.randomUUID()
        whenever(repository.existsById(userId)).thenReturn(false)
        assertThrows(IllegalStateException::class.java) { svc.ensureUserKey(userId) }
    }

    @Test
    fun `wrong-sized KEK rejected`() {
        val tooShort = DataEncryptionProperties(kek = Base64.getEncoder().encodeToString(ByteArray(16)))
        val svc = UserCryptoService(repository, tooShort, clock)
        val userId = UUID.randomUUID()
        whenever(repository.existsById(userId)).thenReturn(false)
        assertThrows(IllegalArgumentException::class.java) { svc.ensureUserKey(userId) }
    }
}
