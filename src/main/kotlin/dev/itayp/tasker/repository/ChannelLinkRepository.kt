package dev.itayp.tasker.repository

import dev.itayp.tasker.channel.ChannelType
import dev.itayp.tasker.jpa.ChannelLinkEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChannelLinkRepository : JpaRepository<ChannelLinkEntity, UUID> {

    fun findByChannelAndExternalIdHmac(channel: ChannelType, externalIdHmac: String): ChannelLinkEntity?

    fun findByUserIdAndChannel(userId: UUID, channel: ChannelType): ChannelLinkEntity?

    fun findByUserId(userId: UUID): List<ChannelLinkEntity>
}
