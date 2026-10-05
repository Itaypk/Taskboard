import type { TFunction } from 'i18next';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router-dom';
import { useBranding } from './publicConfig';

/** Routes with a title of their own; every other route shares the home title. */
const PAGE_TITLE_KEYS: Record<string, string> = {
    '/terms': 'policyPage.termsTitle',
    '/privacy': 'policyPage.privacyTitle',
    '/about': 'policyPage.aboutTitle',
    '/faq': 'policyPage.faqTitle',
};

/**
 * The document title for a route, in the current UI language. The English strings mirror the
 * server-rendered `<title>` in `index.html` (and `ContentPage`), so English visitors see no change.
 */
export function documentTitle(pathname: string, t: TFunction, appName: string): string {
    const key = PAGE_TITLE_KEYS[pathname.replace(/\/+$/, '') || '/'];
    return key ? `${t(key)} — ${appName}` : t('app.documentTitle', { appName });
}

/**
 * Keeps `document.title` in the UI language as the route or the language changes. The single owner
 * of the title, mounted once in `App`: per-page effects would race each other on a language switch
 * (child effects run before parent ones, so a shell-level default would overwrite a page's title).
 *
 * The server-rendered `<title>` and meta tags stay English (docs/archive/I18N.md); this only changes
 * what the tab shows once the SPA is running. Meta tags aren't touched — nothing a person sees reads
 * them after load, and crawlers get the server-rendered English ones.
 */
export function useDocumentTitle(): void {
    const { t } = useTranslation();
    const { pathname } = useLocation();
    const { name: appName } = useBranding();
    const title = documentTitle(pathname, t, appName);
    useEffect(() => {
        document.title = title;
    }, [title]);
}
