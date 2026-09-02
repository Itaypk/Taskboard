package dev.itayp.tasker.service

import org.springframework.stereotype.Service
import java.util.Locale

/**
 * Resolves a request's `Accept-Language` header against the trimmed set of supported UI/comms
 * languages (see docs/I18N.md, D2). Used at the browser-facing edges where we have no stored
 * preference yet: registration-time locale initialization and the pre-auth magic-link email.
 *
 * Matching is RFC 4647 lookup ([Locale.lookupTag]) over the [UserSettingsService.SUPPORTED_LANGUAGES]
 * codes, so a browser sending `he-IL` matches `he`. Lookup only ever *truncates* a range, never
 * extends it, so a bare `en` matches no tag on its own — hence the basic-filtering fallback in
 * [resolveSupportedTag]. The English variants are ordered `en-US` before `en-GB` in the list so
 * that fallback resolves a bare `en` to US English (the historical default) rather than UK.
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
        // Lookup first (most specific single answer), then basic filtering. The fallback exists for
        // ranges that are *shorter* than any supported tag: lookup truncates the range looking for an
        // exact tag, so a bare `en` finds nothing even though `en-US` is right there. Filtering does
        // match it, and preserves LOOKUP_TAGS order, so `en` lands on `en-US`. Anything genuinely
        // unsupported (`de`) filters to empty and still returns null.
        return Locale.lookupTag(ranges, LOOKUP_TAGS)
            ?: Locale.filterTags(ranges, LOOKUP_TAGS).firstOrNull()
    }

    /**
     * Same, but lets a language the visitor picked *explicitly* (the anonymous footer switcher)
     * beat what their browser advertises. Someone reading the site in Hebrew on an `en-US` browser
     * means it: seeding their account from `Accept-Language` would hand them a Hebrew interface
     * with an English backlog and English comms.
     *
     * [explicitTag] is untrusted query input, so it is length-capped before parsing and still has
     * to survive the same supported-list lookup; anything unrecognised falls through to the header.
     */
    fun resolveSupportedTag(explicitTag: String?, acceptLanguage: String?): String? =
        resolveSupportedTag(explicitTag?.take(MAX_EXPLICIT_TAG_LENGTH))
            ?: resolveSupportedTag(acceptLanguage)

    /**
     * Best supported [Locale] for the header, falling back to English when nothing matches. Suitable
     * for the pre-auth magic-link email, which must always render in *some* locale.
     */
    fun resolveLocale(acceptLanguage: String?): Locale =
        resolveSupportedTag(acceptLanguage)?.let(Locale::forLanguageTag) ?: Locale.ENGLISH

    companion object {
        /** Generous next to a real tag (`zh-Hant-TW` is 11), tight enough that parsing can't be abused. */
        private const val MAX_EXPLICIT_TAG_LENGTH = 35

        /** Supported codes ordered for [Locale.lookupTag]; `en-US` precedes `en-GB` so bare `en` → US. */
        private val LOOKUP_TAGS: List<String> = listOf("en-US", "en-GB", "he", "ar", "ru")
    }
}
