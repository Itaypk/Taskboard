package dev.itayp.tasker.channel.email

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.OutboundMessage
import org.slf4j.LoggerFactory

/** No-op email channel used when email is disabled (dev/test): logs instead of sending. */
class LoggingEmailChannel(private val purpose: String = "email") : OutboundChannel {

    private val log = LoggerFactory.getLogger(LoggingEmailChannel::class.java)

    override fun send(message: OutboundMessage) {
        require(message is EmailMessage) { "LoggingEmailChannel only handles EmailMessage" }
        log.info(
            "[EMAIL DISABLED purpose={}] to={} subject='{}' hasIcal={}",
            purpose,
            message.to,
            message.subject,
            message.iCalAttachment != null,
        )
    }
}
