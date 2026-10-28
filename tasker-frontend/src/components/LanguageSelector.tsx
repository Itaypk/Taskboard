import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { applyLocale, resolveUiLanguage, selectableLanguages, type UiLanguage } from '../i18n';
import { getActiveLocale } from '../i18n/format';
import styles from './LanguageSelector.module.css';

/**
 * Language names in their own language. Deliberately not translation keys: a switcher exists so
 * someone who cannot read the current language can escape it, and "Hebrew" is no use to a reader
 * who only reads Hebrew. Endonyms also stay correct without four catalogs to keep in sync.
 */
const ENDONYMS: Record<UiLanguage, string> = {
    en: 'English',
    he: 'עברית',
    ru: 'Русский',
    ar: 'العربية',
};

/**
 * English is offered as its two variants, matching the Settings picker: they share a catalog but
 * not date formats or spelling, and the anonymous choice is what a new account is seeded with
 * (`authApi` forwards the full tag).
 */
const ENGLISH_VARIANTS: readonly { tag: string; label: string }[] = [
    { tag: 'en-US', label: 'English (US)' },
    { tag: 'en-GB', label: 'English (UK)' },
];

function optionsFor(languages: readonly UiLanguage[]): { tag: string; label: string }[] {
    return languages.flatMap(code => (code === 'en' ? ENGLISH_VARIANTS : [{ tag: code, label: ENDONYMS[code] }]));
}

/** The option an active locale tag selects: English collapses to its variant, others to the UI language. */
function optionTagFor(locale: string): string {
    const ui = resolveUiLanguage(locale);
    if (ui !== 'en') return ui;
    return locale.toLowerCase().startsWith('en-gb') ? 'en-GB' : 'en-US';
}

/**
 * Language switcher for the *anonymous* surface (login, policy, invite and email-confirm pages).
 * Signed-in users set their language in Settings, where it is stored on the account and drives
 * email and Telegram too; this only moves the browser, so it is hidden once a user is known.
 *
 * `applyLocale` caches the tag in localStorage and `detectPreferredTag` reads that cache ahead of
 * `navigator.languages`, so a choice made here survives a reload without any extra plumbing.
 *
 * The options come from `selectableLanguages()`, which is the launched set in production and the
 * wider supported set in dev — this replaced the old `?uiLang=` preview flag.
 */
export default function LanguageSelector({ className }: { className?: string } = {}) {
    const { t } = useTranslation();
    // Local state, set synchronously on change, rather than deriving purely from
    // `getActiveLocale()` at render time: `applyLocale` is async, and the i18next
    // `languageChanged` event that would otherwise trigger a re-render can fire before
    // `setActiveLocale` runs, leaving the dropdown showing the previous selection.
    const [current, setCurrent] = useState(() => optionTagFor(getActiveLocale()));

    return (
        <select
            className={`${styles.select} ${className ?? ''}`.trim()}
            value={current}
            aria-label={t('footer.language')}
            onChange={e => {
                const next = e.target.value;
                setCurrent(next);
                void applyLocale(next);
            }}
        >
            {optionsFor(selectableLanguages()).map(({ tag, label }) => (
                <option key={tag} value={tag}>{label}</option>
            ))}
        </select>
    );
}
