package dev.itayp.tasker.channel.email

import dev.itayp.tasker.channel.OutboundChannel
import dev.itayp.tasker.channel.OutboundMessage
import org.slf4j.LoggerFactory

/**
 * No-op email channel used when email is disabled: logs that a message was dropped instead of
 * sending it. Deliberately leaves out the addresses, subject and body — this also runs in
 * production when an instance has no SMTP configured, a calendar invite's subject is the task's
 * title, and the body of a login email is a live credential.
 */
class LoggingEmailChannel(private val purpose: String = "email") : OutboundChannel {

    private val log = LoggerFactory.getLogger(LoggingEmailChannel::class.java)

    override fun send(message: OutboundMessage) {
        require(message is EmailMessage) { "LoggingEmailChannel only handles EmailMessage" }
        log.info(
            "[EMAIL DISABLED purpose={}] dropped a message to {} recipient(s), hasIcal={}",
            purpose,
            message.to.size,
            message.iCalAttachment != null,
        )
    }
}
