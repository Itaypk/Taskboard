package dev.itayp.tasker.crypto

import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.util.Base64
import java.util.Optional
import java.util.UUID

/**
 * Real UserCryptoService backed by an in-memory key store. Use when you want crypto
 * to behave like production (real AES-GCM, AAD enforcement) without spinning up the DB.
 * Tests should call `ensureUserKey(userId)` before encrypting for any user.
 */
fun newTestUserCryptoService(): UserCryptoService {
    val store = HashMap<UUID, UserDataKeyEntity>()
    val repo = mock<UserDataKeyRepository> {
        on { existsById(any()) } doAnswer { store.containsKey(it.arguments[0] as UUID) }
        on { findById(any()) } doAnswer { Optional.ofNullable(store[it.arguments[0] as UUID]) }
    }
    whenever(repo.save(any<UserDataKeyEntity>())).thenAnswer {
        val entity = it.arguments[0] as UserDataKeyEntity
        store[entity.userId!!] = entity
        entity
    }
    val props = DataEncryptionProperties(
        kek = Base64.getEncoder().encodeToString(ByteArray(32) { 0x42 })
    )
    return UserCryptoService(repo, props, Clock.systemUTC())
}

/**
 * Identity-only stub: encrypt(s) -> s.toByteArray(UTF-8); decrypt(b) -> String(b). No
 * real crypto, no DEK lifecycle. Lets existing tests do `entity.title = noopCrypto.encrypt(userId, "raw")`
 * and assert against plaintext without worrying about AAD/keys. Use for tests where the
 * encryption layer is not what's under test.
 */
fun noopUserCryptoService(): UserCryptoService {
    // Use Mockito.lenient() so unused stubs don't fail tests under strict stubbing —
    // many tests inject this helper without touching crypto in every code path.
    val mock = mock<UserCryptoService>()
    Mockito.lenient().`when`(mock.encrypt(any(), anyOrNull<String>())).thenAnswer {
        val plaintext = it.arguments[1] as String?
        plaintext?.toByteArray(Charsets.UTF_8)
    }
    Mockito.lenient().`when`(mock.decrypt(any(), anyOrNull<ByteArray>())).thenAnswer {
        val ciphertext = it.arguments[1] as ByteArray?
        ciphertext?.toString(Charsets.UTF_8)
    }
    return mock
}

/**
 * Identity-only stub for [BoardCryptoService], the board-scoped twin of [noopUserCryptoService]:
 * encrypt(boardId, s) -> s.toByteArray(UTF-8); decrypt(boardId, b) -> String(b). Use for tests
 * where board content crypto is not what's under test.
 */
fun noopBoardCryptoService(): BoardCryptoService {
    val mock = mock<BoardCryptoService>()
    Mockito.lenient().`when`(mock.encrypt(any(), anyOrNull<String>())).thenAnswer {
        val plaintext = it.arguments[1] as String?
        plaintext?.toByteArray(Charsets.UTF_8)
    }
    Mockito.lenient().`when`(mock.decrypt(any(), anyOrNull<ByteArray>())).thenAnswer {
        val ciphertext = it.arguments[1] as ByteArray?
        ciphertext?.toString(Charsets.UTF_8)
    }
    return mock
}
