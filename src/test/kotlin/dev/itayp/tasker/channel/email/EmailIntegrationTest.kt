package dev.itayp.tasker.channel.email

import dev.itayp.tasker.EnvTest
import dev.itayp.tasker.channel.email.EmailTemplateEngine
import org.springframework.context.support.StaticMessageSource
import dev.itayp.tasker.channel.email.invitation.CalendarEvent
import dev.itayp.tasker.channel.email.invitation.CalendarInvitationComposer
import org.junit.jupiter.api.Test
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Properties

/**
 * Manual integration tests that send real emails over SMTP.
 *
 * They are skipped automatically unless `.env.test` in the project root
 * contains all required keys (see `.env.test.example`).
 *
 * Run a single test from the command line:
 *   ./gradlew test --tests "dev.itayp.tasker.channel.email.EmailIntegrationTest.*"
 */
class EmailIntegrationTest {

    private companion object {
        val REQUIRED_KEYS = arrayOf(
            "TASKER_EMAIL_SMTP_HOST",
            "TASKER_EMAIL_SMTP_PORT",
            "TASKER_EMAIL_SMTP_USERNAME",
            "TASKER_EMAIL_SMTP_PASSWORD",
            "TASKER_EMAIL_FROM",
            "TEST_EMAIL_TO",
        )
    }

    /** Builds a live SMTP channel from `.env.test` values, or skips the test. */
    private fun buildChannel(env: Map<String, String>): SmtpEmailChannel {
        val props = EmailProperties(
            enabled = true,
            from = env.getValue("TASKER_EMAIL_FROM"),
            fromName = env.getOrDefault("TASKER_EMAIL_FROM_NAME", "Backlog.fyi Test"),
            smtp = EmailProperties.SmtpConfig(
                host = env.getValue("TASKER_EMAIL_SMTP_HOST"),
                port = env.getValue("TASKER_EMAIL_SMTP_PORT").toInt(),
                username = env.getValue("TASKER_EMAIL_SMTP_USERNAME"),
                password = env.getValue("TASKER_EMAIL_SMTP_PASSWORD"),
            ),
        )
        val sender = JavaMailSenderImpl().apply {
            host = props.smtp.host
            port = props.smtp.port
            username = props.smtp.username
            password = props.smtp.password
            javaMailProperties = Properties().apply {
                setProperty("mail.smtp.auth", "true")
                setProperty("mail.smtp.starttls.enable", "true")
                setProperty("mail.smtp.starttls.required", "true")
            }
        }
        return SmtpEmailChannel(sender, props)
    }

    @Test
    fun `sends a simple HTML email`() {
        val env = EnvTest.requireEnv(*REQUIRED_KEYS)
        val channel = buildChannel(env)

        channel.send(
            EmailMessage(
                to = listOf(env.getValue("TEST_EMAIL_TO")),
                subject = "[Backlog.fyi Integration Test] Simple HTML email",
                htmlBody = """
                    <h1>Integration test — simple email</h1>
                    <p>This message was sent by <code>EmailIntegrationTest</code>.</p>
                    <p>If you're reading this, SMTP delivery is working correctly.</p>
                """.trimIndent(),
                textBody = "Integration test — simple email. If you're reading this, SMTP delivery is working correctly.",
            ),
        )
    }

    @Test
    fun `sends a calendar invitation email with ICS attachment`() {
        val env = EnvTest.requireEnv(*REQUIRED_KEYS)
        val channel = buildChannel(env)
        val to = env.getValue("TEST_EMAIL_TO")
        val from = env.getValue("TASKER_EMAIL_FROM")

        val messageSource = StaticMessageSource().apply {
            addMessage("email.invitation.when", java.util.Locale.ENGLISH, "When")
            addMessage("email.invitation.location", java.util.Locale.ENGLISH, "Location")
            addMessage("email.invitation.description", java.util.Locale.ENGLISH, "Description")
            addMessage("email.invitation.footer", java.util.Locale.ENGLISH, "Footer")
        }
        val emailTemplateEngine = EmailTemplateEngine(messageSource)
        val composer = CalendarInvitationComposer(channel, emailTemplateEngine)

        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val event = CalendarEvent(
            title = "[Backlog.fyi Integration Test] Team Planning Session",
            description = "Weekly planning session.\n\nAgenda:\n- Review backlog\n- Agree on this week's tasks",
            start = now.plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0),
            end = now.plusDays(1).withHour(11).withMinute(0).withSecond(0).withNano(0),
            location = "https://meet.example.com/test-room",
            organizerEmail = from,
            organizerName = env.getOrDefault("TASKER_EMAIL_FROM_NAME", "Backlog.fyi Test"),
            attendeeEmails = listOf(to),
        )

        composer.sendInvitation(listOf(to), event)
    }
}
