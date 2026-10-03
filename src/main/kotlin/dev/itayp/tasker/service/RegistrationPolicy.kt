package dev.itayp.tasker.service

import dev.itayp.tasker.config.AuthProperties
import dev.itayp.tasker.config.AuthProperties.RegistrationMode
import dev.itayp.tasker.jpa.AuthProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ResponseStatus

/** Decides whether a login may create a new account. See [AuthProperties.registration]. */
@Component
class RegistrationPolicy(private val authProperties: AuthProperties) {

    val open: Boolean get() = authProperties.registration == RegistrationMode.OPEN

    /** The demo sandbox creates an account per visit, so it needs open registration too. */
    val demoAvailable: Boolean get() = open && authProperties.demoEnabled

    /** Operator-listed local users are provisioned, not self-registered, so they're always allowed. */
    fun allowsNewAccount(provider: String): Boolean = open || provider == AuthProvider.LOCAL

    fun requireNewAccountAllowed(provider: String) {
        if (!allowsNewAccount(provider)) throw RegistrationClosedException()
    }

    fun requireDemoAvailable() {
        if (!demoAvailable) throw RegistrationClosedException()
    }
}

/** A login would have created an account while registration is closed. Maps to HTTP 403. */
@ResponseStatus(HttpStatus.FORBIDDEN)
class RegistrationClosedException : RuntimeException("This instance isn't accepting new accounts")
