package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the in-app feedback form. Submissions are emailed to [recipient]
 * through the existing **auth** email sender (same SMTP credentials as the login /
 * verification mailbox). When [recipient] is blank the service falls back to the auth
 * sender's own `from` address, so feedback still lands in an owner-controlled mailbox.
 */
@ConfigurationProperties("tasker.feedback")
data class FeedbackProperties(
    val recipient: String = "",
)
