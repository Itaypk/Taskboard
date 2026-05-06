package dev.itayp.tasker.channel.email

import dev.itayp.tasker.channel.OutboundMessage

data class EmailMessage(
    val to: List<String>,
    val subject: String,
    val htmlBody: String,
    val textBody: String? = null,
    val iCalAttachment: ICalAttachment? = null,
) : OutboundMessage

data class ICalAttachment(
    val method: String = "REQUEST",
    val content: String,
    val filename: String = "invite.ics",
)
