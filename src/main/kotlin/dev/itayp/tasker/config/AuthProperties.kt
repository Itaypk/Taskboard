package dev.itayp.tasker.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Who may get an account, and the operator-managed password logins. The defaults (open
 * registration, demo on, no local users) are the hosted instance's behavior; a self-hosted instance
 * typically closes registration, turns the demo off and lists its users here instead.
 */
@ConfigurationProperties("tasker.auth")
data class AuthProperties(
    val registration: RegistrationMode = RegistrationMode.OPEN,
    /** The zero-registration sandbox. Only offered while [registration] is open, since it creates accounts. */
    val demoEnabled: Boolean = true,
    val local: LocalUsers = LocalUsers(),
) {
    enum class RegistrationMode {
        /** Anyone may create an account through any enabled login method. */
        OPEN,

        /**
         * No new accounts, except the operator's [LocalUsers]. Existing accounts keep signing in and
         * linking methods; an unknown Telegram or email login is refused.
         */
        CLOSED,
    }

    /**
     * Username/password logins the operator lists in configuration — there is no sign-up, change or
     * reset flow. Each entry is `username:bcrypt-hash`, the format `htpasswd -nbB user password`
     * prints. Entries come from [users] (e.g. `TASKER_LOCAL_USERS`, comma-separated) and/or
     * [usersFile], an htpasswd-style file with one entry per line, which avoids escaping the `$`s in
     * bcrypt hashes for Docker Compose.
     */
    data class LocalUsers(
        val users: List<String> = emptyList(),
        val usersFile: String = "",
    )
}
