package dev.itayp.tasker.security.google

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GoogleAuthDataTest {

    private fun data(email: String?, verified: Boolean = true, hd: String? = null) =
        GoogleAuthData(subject = "s", email = email, emailVerified = verified, hostedDomain = hd)

    @Test
    fun `a verified Gmail address is trusted, normalized`() {
        assertThat(data(" Alice@Gmail.com ").trustedEmail).isEqualTo("alice@gmail.com")
        assertThat(data("bob@googlemail.com").trustedEmail).isEqualTo("bob@googlemail.com")
    }

    @Test
    fun `a verified Workspace address is trusted`() {
        assertThat(data("alice@acme.test", hd = "acme.test").trustedEmail).isEqualTo("alice@acme.test")
    }

    @Test
    fun `a verified address on another domain is not trusted`() {
        assertThat(data("alice@outlook.com").trustedEmail).isNull()
    }

    @Test
    fun `an unverified or missing address is never trusted`() {
        assertThat(data("alice@gmail.com", verified = false).trustedEmail).isNull()
        assertThat(data(null).trustedEmail).isNull()
        assertThat(data("  ").trustedEmail).isNull()
    }
}
