package dev.itayp.tasker.service

import dev.itayp.tasker.config.AuthProperties
import dev.itayp.tasker.jpa.AuthProvider
import dev.itayp.tasker.jpa.UserEntity
import dev.itayp.tasker.ratelimit.RateLimiter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ResponseStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Username/password login for the operator-listed users in [AuthProperties.LocalUsers] — meant for
 * self-hosted instances that have neither SMTP nor Telegram set up. Deliberately minimal: the list
 * is read once at startup, there's no sign-up, password change or reset, and a password change in
 * configuration doesn't end existing sessions (the user can sign out other sessions from Settings).
 *
 * Each user maps to an `auth_identities` row (`local`, lowercase username), so the account is
 * created on first login through the same [UserAuthService.loginOrRegister] path as every other
 * provider, and survives restarts and later changes to the password.
 */
@Service
class LocalLoginService(
    authProperties: AuthProperties,
    private val userAuthService: UserAuthService,
    @Qualifier("passwordLoginRateLimiter") private val rateLimiter: RateLimiter,
) {

    private val log = LoggerFactory.getLogger(LocalLoginService::class.java)
    private val encoder = BCryptPasswordEncoder()

    /** Lowercase username → bcrypt hash. Invalid entries fail startup rather than locking someone out silently. */
    private val users: Map<String, String> = loadUsers(authProperties.local)

    /** Compared against when the username is unknown, so both paths cost one bcrypt check. */
    private val dummyHash: String = encoder.encode(UUID.randomUUID().toString())!!

    val enabled: Boolean get() = users.isNotEmpty()

    val userCount: Int get() = users.size

    /**
     * Returns the signed-in user, or null for a wrong username or password — the two are
     * indistinguishable to the caller, and an unknown username still pays for a bcrypt comparison so
     * response timing doesn't reveal which usernames exist.
     *
     * Throttled per client IP and per username; either budget running out throws
     * [TooManyLoginAttemptsException] without checking the password at all.
     */
    fun login(username: String, password: String, clientIp: String, hints: RegistrationHints = RegistrationHints.NONE): UserEntity? {
        val key = normalise(username)
        if (!rateLimiter.tryConsume("ip:$clientIp") || !rateLimiter.tryConsume("user:$key")) {
            log.info("Password login rate-limited")
            throw TooManyLoginAttemptsException()
        }
        val hash = users[key]
        val matches = encoder.matches(password, hash ?: dummyHash)
        if (hash == null || !matches) {
            log.info("Password login failed")
            return null
        }
        return userAuthService.loginOrRegister(
            provider = AuthProvider.LOCAL,
            providerUserId = key,
            verified = true,
            onExisting = {},
            onCreate = {},
            hints = hints,
        )
    }

    companion object {
        private val USERNAME = Regex("^[a-z0-9._@-]{1,64}$")
        private val BCRYPT = Regex("^\\$2[aby]?\\$\\d{2}\\$[./A-Za-z0-9]{53}$")

        fun normalise(username: String): String = username.trim().lowercase()

        internal fun loadUsers(config: AuthProperties.LocalUsers): Map<String, String> {
            val fromFile = config.usersFile.takeIf { it.isNotBlank() }?.let { location ->
                val path = Path.of(location)
                // A missing file is a configuration mistake (often a mount that didn't happen), so say
                // which setting points where instead of surfacing a bare NoSuchFileException.
                require(Files.isRegularFile(path)) {
                    "TASKER_LOCAL_USERS_FILE points to $location, which isn't a readable file"
                }
                Files.readAllLines(path).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            }.orEmpty()
            val entries = config.users.map { it.trim() }.filter { it.isNotEmpty() } + fromFile
            val users = LinkedHashMap<String, String>()
            entries.forEachIndexed { index, entry ->
                val separator = entry.indexOf(':')
                require(separator > 0) { "Local user entry #${index + 1} must be 'username:bcrypt-hash'" }
                val username = normalise(entry.substring(0, separator))
                val hash = entry.substring(separator + 1).trim().removePrefix("{bcrypt}")
                require(USERNAME.matches(username)) {
                    "Local username '$username' must be 1-64 characters of a-z, 0-9, '.', '_', '@' or '-'"
                }
                // Never echo the hash itself: it's a credential, and error messages end up in logs.
                require(BCRYPT.matches(hash)) {
                    "Local user '$username' needs a bcrypt hash (e.g. from `htpasswd -nbB $username <password>`)"
                }
                require(users.put(username, hash) == null) { "Local user '$username' is listed more than once" }
            }
            return users
        }
    }
}

/** Too many password attempts from this client or for this username. Maps to HTTP 429. */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
class TooManyLoginAttemptsException : RuntimeException("Too many sign-in attempts, please try again later")
