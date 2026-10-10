package dev.itayp.tasker.service

import org.springframework.stereotype.Component

/**
 * What the request that might create an account knows about the visitor, used to seed a *new*
 * account's settings; an existing account ignores it on login. Every field is optional and already
 * validated (see [RegistrationHintsResolver]), so a null means "keep the default".
 */
data class RegistrationHints(
    /** A supported language code (docs/I18N.md, D2). */
    val language: String? = null,
    /** A zone from [UserSettingsService.SUPPORTED_TIME_ZONES], as the browser reported it. */
    val timeZone: String? = null,
) {
    companion object {
        val NONE = RegistrationHints()
    }
}

/**
 * Builds [RegistrationHints] at the browser-facing edges that can register an account. Both inputs
 * are untrusted query parameters: the language goes through the same negotiation as before (an
 * explicit pick beats `Accept-Language`), and an unknown time zone is dropped rather than rejected,
 * since there is nothing the visitor could do about their browser's answer.
 */
@Component
class RegistrationHintsResolver(private val localeNegotiationService: LocaleNegotiationService) {

    fun resolve(lang: String?, acceptLanguage: String?, timeZone: String?): RegistrationHints =
        RegistrationHints(
            language = localeNegotiationService.resolveSupportedTag(lang, acceptLanguage),
            timeZone = resolveTimeZone(timeZone),
        )

    /** [timeZone] if it is a supported zone, else null. */
    fun resolveTimeZone(timeZone: String?): String? = timeZone?.takeIf { it in SUPPORTED_TIME_ZONES }

    private companion object {
        val SUPPORTED_TIME_ZONES: Set<String> = UserSettingsService.SUPPORTED_TIME_ZONES.toSet()
    }
}
