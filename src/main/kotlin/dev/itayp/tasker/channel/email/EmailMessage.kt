package dev.itayp.tasker.channel.email

import dev.itayp.tasker.channel.OutboundMessage

data class EmailMessage(
    val to: List<String>,
    val subject: String,
    val htmlBody: String,
    val textBody: String? = null,
    val iCalAttachment: ICalAttachment? = null,
    /**
     * Optional Reply-To address. The message is still sent from the sender's own `from` mailbox
     * (so SMTP submission auth is unaffected), but replies are directed here — e.g. a feedback
     * submitter's address, so the recipient can reply straight back to them.
     */
    val replyTo: String? = null,
) : OutboundMessage

data class ICalAttachment(
    val method: String = "REQUEST",
    val content: String,
    val filename: String = "invite.ics",
)
