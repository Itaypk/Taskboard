import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import LanguageSelector from './LanguageSelector';
import styles from './AppFooter.module.css';

const LINKS = [
    { to: '/about', key: 'about' },
    { to: '/faq', key: 'faq' },
    { to: '/terms', key: 'terms' },
    { to: '/privacy', key: 'privacy' },
] as const;

/** Public source repository (AGPL-3.0). Update here if the repository is renamed or moved. */
const SOURCE_URL = 'https://github.com/Itaypk/Taskboard';

/** Mirrors the `max-width: 600px` breakpoint the rest of the app uses for phone layouts. */
const COMPACT_QUERY = '(max-width: 600px)';

function subscribeToCompact(onChange: () => void): () => void {
    const mq = window.matchMedia?.(COMPACT_QUERY);
    if (!mq) return () => {};
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
}

const isCompact = () => window.matchMedia?.(COMPACT_QUERY).matches ?? false;

/**
 * Rendered once below the router, so the legal/info pages are reachable from everywhere —
 * signed in or not. It shares its row with the board's "show archived" toggle, which is
 * centred over the same strip: the links sit at the inline edge on wide screens, and fold
 * into a menu on phones where a centred toggle would run into them.
 *
 * [showLanguage] adds the anonymous language switcher. It is a prop rather than an auth lookup so
 * this stays presentational chrome: `App` knows who is signed in, and a signed-in user changes
 * language in Settings, where the choice is stored on the account and also reaches email and
 * Telegram (docs/I18N.md, D2).
 */
export default function AppFooter({ showLanguage = false }: { showLanguage?: boolean } = {}) {
    const { t } = useTranslation();
    const compact = useSyncExternalStore(subscribeToCompact, isCompact, () => false);
    const [open, setOpen] = useState(false);
    const wrapRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!open) return;
        const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
        const onClick = (e: MouseEvent) => {
            if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
        };
        window.addEventListener('keydown', onKey);
        window.addEventListener('mousedown', onClick);
        return () => {
            window.removeEventListener('keydown', onKey);
            window.removeEventListener('mousedown', onClick);
        };
    }, [open]);

    if (!compact) {
        return (
            <footer className={styles.footer}>
                {LINKS.map(({ to, key }) => (
                    <Link key={to} to={to} className={styles.link}>{t(`footer.${key}`)}</Link>
                ))}
                <a href={SOURCE_URL} className={styles.link} target="_blank" rel="noopener noreferrer">
                    {t('footer.github')}
                </a>
                {showLanguage && <LanguageSelector />}
            </footer>
        );
    }

    return (
        <footer className={styles.footer}>
            <div className={styles.menuWrap} ref={wrapRef}>
                <button
                    type="button"
                    className={styles.trigger}
                    onClick={() => setOpen(o => !o)}
                    aria-label={t('footer.menuLabel')}
                    aria-haspopup="menu"
                    aria-expanded={open}
                >
                    ⋮
                </button>
                {open && (
                    <ul className={styles.menu} role="menu">
                        {LINKS.map(({ to, key }) => (
                            <li key={to} role="none">
                                <Link role="menuitem" to={to} className={styles.menuItem} onClick={() => setOpen(false)}>
                                    {t(`footer.${key}`)}
                                </Link>
                            </li>
                        ))}
                        <li role="none">
                            <a
                                role="menuitem"
                                href={SOURCE_URL}
                                className={styles.menuItem}
                                target="_blank"
                                rel="noopener noreferrer"
                                onClick={() => setOpen(false)}
                            >
                                {t('footer.github')}
                            </a>
                        </li>
                        {showLanguage && (
                            <li role="none" className={styles.menuLanguage}>
                                <LanguageSelector />
                            </li>
                        )}
                    </ul>
                )}
            </div>
        </footer>
    );
}
