package dev.itayp.tasker.channel.email

import dev.itayp.tasker.channel.OutboundChannel
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.util.*

/**
 * Wires the two email senders. Each becomes a qualified [OutboundChannel] bean
 * (`authEmailChannel`, `schedulingEmailChannel`) that is metered for delivery
 * tracking. When email is disabled, both fall back to a [LoggingEmailChannel] so
 * dev/test still exercise the call paths (and the magic link is printed to the log).
 */
@Configuration
@EnableConfigurationProperties(EmailProperties::class)
class EmailConfiguration(
    private val properties: EmailProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(EmailConfiguration::class.java)

    init {
        log.debug(
            "Email enabled: {}, authentication SMTP: {}, scheduling SMTP: {}, {} blocked domains",
            properties.enabled, properties.auth, properties.scheduling, properties.blockedDomains.size,
        )
    }

    @Bean
    fun authEmailChannel(): OutboundChannel = buildChannel(properties.auth, AUTH)

    @Bean
    fun schedulingEmailChannel(): OutboundChannel = buildChannel(properties.scheduling, SCHEDULING)

    private fun buildChannel(sender: EmailProperties.SenderConfig, purpose: String): OutboundChannel {
        val base: OutboundChannel = if (properties.enabled) {
            SmtpEmailChannel(mailSender(sender.smtp), sender.from, sender.fromName)
        } else {
            LoggingEmailChannel(purpose)
        }
        // Auth sends are synchronous on the request path and the user can simply re-request the
        // link, so only the async scheduling sender retries. Retry sits inside metrics on purpose.
        val delivering = if (purpose == SCHEDULING) RetryingOutboundChannel(base, meterRegistry, purpose) else base
        return EmailMetricsOutboundChannel(delivering, meterRegistry, purpose)
    }

    private fun mailSender(smtp: EmailProperties.SmtpConfig): JavaMailSender =
        JavaMailSenderImpl().apply {
            host = smtp.host
            port = smtp.port
            username = smtp.username
            password = smtp.password
            javaMailProperties = javaMailProperties(smtp.port)
        }

    companion object {
        const val AUTH = "auth"
        const val SCHEDULING = "scheduling"

        private const val SMTPS_PORT = 465

        fun javaMailProperties(port: Int): Properties = Properties().apply {
            setProperty("mail.smtp.auth", "true")
            // 465 is implicit TLS (SMTPS): the server expects a TLS handshake first, so a STARTTLS
            // client just waits for a greeting until the connection times out.
            if (port == SMTPS_PORT) {
                setProperty("mail.smtp.ssl.enable", "true")
            } else {
                setProperty("mail.smtp.starttls.enable", "true")
                setProperty("mail.smtp.starttls.required", "true")
            }
        }
    }
}
