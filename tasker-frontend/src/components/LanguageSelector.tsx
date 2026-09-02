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
    const current = resolveUiLanguage(getActiveLocale());

    return (
        <select
            className={`${styles.select} ${className ?? ''}`.trim()}
            value={current}
            aria-label={t('footer.language')}
            onChange={e => { void applyLocale(e.target.value); }}
        >
            {selectableLanguages().map(code => (
                <option key={code} value={code}>{ENDONYMS[code]}</option>
            ))}
        </select>
    );
}
