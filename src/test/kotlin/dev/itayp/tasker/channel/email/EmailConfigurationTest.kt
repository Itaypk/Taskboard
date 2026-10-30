package dev.itayp.tasker.channel.email

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmailConfigurationTest {

    @Test
    fun `port 587 requires STARTTLS`() {
        val props = EmailConfiguration.javaMailProperties(587)

        assertThat(props.getProperty("mail.smtp.auth")).isEqualTo("true")
        assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("true")
        assertThat(props.getProperty("mail.smtp.starttls.required")).isEqualTo("true")
        assertThat(props.getProperty("mail.smtp.ssl.enable")).isNull()
    }

    @Test
    fun `port 465 uses implicit TLS instead of STARTTLS`() {
        val props = EmailConfiguration.javaMailProperties(465)

        assertThat(props.getProperty("mail.smtp.auth")).isEqualTo("true")
        assertThat(props.getProperty("mail.smtp.ssl.enable")).isEqualTo("true")
        assertThat(props.getProperty("mail.smtp.starttls.enable")).isNull()
        assertThat(props.getProperty("mail.smtp.starttls.required")).isNull()
    }
}
