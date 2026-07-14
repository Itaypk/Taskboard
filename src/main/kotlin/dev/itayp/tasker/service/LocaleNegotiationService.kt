package dev.itayp.tasker.service

import org.springframework.stereotype.Service
import java.util.Locale

/**
 * Resolves a request's `Accept-Language` header against the trimmed set of supported UI/comms
 * languages (see docs/I18N.md, D2). Used at the browser-facing edges where we have no stored
 * preference yet: registration-time locale initialization and the pre-auth magic-link email.
 *
 * Matching is RFC 4647 lookup ([Locale.lookupTag]) over the [UserSettingsService.SUPPORTED_LANGUAGES]
 * codes, so a browser sending `he-IL` matches `he` and `en` matches an English variant. The English
 * variants are ordered `en-US` before `en-GB` in the lookup list so a bare `en` range resolves to
 * US English (the historical default) rather than UK.
 */
@Service
class LocaleNegotiationService {

    /**
     * Best supported language *code* for the given `Accept-Language` header value, or `null` when
     * the header is blank, malformed, or matches nothing supported. Callers treat `null` as
     * "keep the existing default" (the stored `en-US`).
     */
    fun resolveSupportedTag(acceptLanguage: String?): String? {
        if (acceptLanguage.isNullOrBlank()) return null
        val ranges = runCatching { Locale.LanguageRange.parse(acceptLanguage) }.getOrNull() ?: return null
        if (ranges.isEmpty()) return null
        return Locale.lookupTag(ranges, LOOKUP_TAGS)
    }

    /**
     * Best supported [Locale] for the header, falling back to English when nothing matches. Suitable
     * for the pre-auth magic-link email, which must always render in *some* locale.
     */
    fun resolveLocale(acceptLanguage: String?): Locale =
        resolveSupportedTag(acceptLanguage)?.let(Locale::forLanguageTag) ?: Locale.ENGLISH

    companion object {
        /** Supported codes ordered for [Locale.lookupTag]; `en-US` precedes `en-GB` so bare `en` → US. */
        private val LOOKUP_TAGS: List<String> = listOf("en-US", "en-GB", "he", "ar", "ru")
    }
}
