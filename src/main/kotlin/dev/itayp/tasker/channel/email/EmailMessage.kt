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
    /**
     * The `Message-ID` (with its angle brackets) of the email this one answers. Sets `In-Reply-To`
     * and `References`, so mail clients thread it under that message. Dropped unless it is a
     * plain `<…>` token: it can come from someone else's email.
     */
    val inReplyTo: String? = null,
) : OutboundMessage

data class ICalAttachment(
    val method: String = "REQUEST",
    val content: String,
    val filename: String = "invite.ics",
)
