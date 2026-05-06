package dev.itayp.tasker.channel.email

import dev.itayp.tasker.channel.OutboundChannel
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.util.Properties

@Configuration
@EnableConfigurationProperties(EmailProperties::class)
class EmailConfiguration(private val properties: EmailProperties) {

    @Bean
    @ConditionalOnProperty(prefix = "tasker.email", name = ["enabled"], havingValue = "true")
    fun javaMailSender(): JavaMailSender {
        val sender = JavaMailSenderImpl().apply {
            host = properties.smtp.host
            port = properties.smtp.port
            username = properties.smtp.username
            password = properties.smtp.password
        }
        sender.javaMailProperties = Properties().apply {
            setProperty("mail.smtp.auth", "true")
            setProperty("mail.smtp.starttls.enable", "true")
            setProperty("mail.smtp.starttls.required", "true")
        }
        return sender
    }

    @Bean
    @ConditionalOnProperty(prefix = "tasker.email", name = ["enabled"], havingValue = "true")
    fun smtpEmailChannel(mailSender: JavaMailSender): OutboundChannel =
        SmtpEmailChannel(mailSender, properties)

    @Bean
    @ConditionalOnProperty(
        prefix = "tasker.email",
        name = ["enabled"],
        havingValue = "false",
        matchIfMissing = true,
    )
    fun loggingEmailChannel(): OutboundChannel = LoggingEmailChannel()
}
