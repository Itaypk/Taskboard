package dev.itayp.tasker.repository

import dev.itayp.tasker.channel.ChannelType
import dev.itayp.tasker.jpa.ChannelLinkTokenEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChannelLinkTokenRepository : JpaRepository<ChannelLinkTokenEntity, UUID> {

    fun findByTokenHmac(tokenHmac: String): ChannelLinkTokenEntity?

    fun findByUserIdAndChannel(userId: UUID, channel: ChannelType): ChannelLinkTokenEntity?
}
