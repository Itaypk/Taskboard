package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.AuthIdentityEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AuthIdentityRepository : JpaRepository<AuthIdentityEntity, UUID> {

    /** Resolve a login identity to its row; the unique key guarantees at most one match. */
    fun findByProviderAndProviderUserId(provider: String, providerUserId: String): AuthIdentityEntity?

    /** All identities attached to a user — used by account-linking and profile screens. */
    fun findAllByUserId(userId: UUID): List<AuthIdentityEntity>

    /** Whether a user has any login method — demo users deliberately have none. */
    fun existsByUserId(userId: UUID): Boolean
}
