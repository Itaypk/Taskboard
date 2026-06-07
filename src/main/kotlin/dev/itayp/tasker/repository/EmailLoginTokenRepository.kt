package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.EmailLoginTokenEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface EmailLoginTokenRepository : JpaRepository<EmailLoginTokenEntity, String> {

    /** Recent unconsumed tokens for an address — used to rate-limit the send endpoint. */
    fun countByEmailHashAndCreatedAtAfter(emailHash: String, since: Instant): Long

    @Modifying
    @Query("DELETE FROM EmailLoginTokenEntity t WHERE t.expiresAt < :cutoff")
    fun deleteAllExpired(@Param("cutoff") cutoff: Instant): Int
}
