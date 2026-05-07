package dev.itayp.tasker.channel.email

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.OutboundMessage
import org.slf4j.LoggerFactory

class LoggingEmailChannel : OutboundChannel {

    private val log = LoggerFactory.getLogger(LoggingEmailChannel::class.java)

    override fun send(message: OutboundMessage) {
        require(message is EmailMessage) { "LoggingEmailChannel only handles EmailMessage" }
        log.info(
            "[EMAIL DISABLED] to={} subject='{}' hasIcal={}",
            message.to,
            message.subject,
            message.iCalAttachment != null,
        )
    }
}
