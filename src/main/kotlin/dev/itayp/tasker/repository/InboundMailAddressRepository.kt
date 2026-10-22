package dev.itayp.tasker.repository

import dev.itayp.tasker.jpa.InboundMailAddressEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface InboundMailAddressRepository : JpaRepository<InboundMailAddressEntity, UUID> {

    /** Delivery lookup. The HMAC is unique, so this is at most one row. */
    fun findByTokenHmac(tokenHmac: String): InboundMailAddressEntity?
}
