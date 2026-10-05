import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import MarkdownRenderer from '../components/MarkdownRenderer';
import { usePublicConfig, type PublicConfig } from '../publicConfig';
import tosContent from './tos.md?raw';
import ppContent from './privacy-policy.md?raw';
import aboutContent from './about.md?raw';
import faqContent from './faq.md?raw';
import { Arrow } from '../components/Arrow';
import styles from './PolicyPage.module.css';

/** The privacy policy's AI data-retention sentence, present only while it's true. Mirrors `AI_RETENTION_NOTE` in `ContentPage.kt`. */
const AI_RETENTION_NOTE =
    "Requests to AI providers are only routed to endpoints with a zero-data-retention policy: the " +
    "provider doesn't store your input or its response once the request completes.";

/**
 * Fill the instance's name, URL, contact addresses and settings into the raw policy markdown. Same
 * placeholders as the server's `ContentPageRenderer`, which renders these pages for clients without
 * JavaScript; the SPA is always served from the instance's own origin, so that stands in for its base URL.
 */
function fillPlaceholders(md: string, config: PublicConfig): string {
    const { branding } = config;
    return md
        .replaceAll('{{APP_NAME}}', branding.name)
        .replaceAll('{{APP_URL}}', window.location.origin)
        .replaceAll('{{SUPPORT_EMAIL}}', branding.supportEmail)
        .replaceAll('{{ABUSE_EMAIL}}', branding.abuseEmail)
        .replaceAll('{{AI_RETENTION_NOTE}}', config.aiZeroDataRetention ? AI_RETENTION_NOTE : '');
}

interface PolicyPageProps {
    title: string;
    body: string;
}

function PolicyPage({ title, body }: PolicyPageProps) {
    const { t, i18n } = useTranslation();
    const config = usePublicConfig();
    const { branding } = config;
    // `document.title` is owned by `useDocumentTitle` in `App`. The page text stays English
    // (docs/archive/I18N.md), so say so to anyone reading the page in another language, and mark the
    // text as English so screen readers don't read it with the UI language's voice and an RTL UI
    // doesn't mirror it.
    const englishOnly = i18n.resolvedLanguage !== 'en';

    return (
        <main className="board-wrap">
            {/* Same markup and global classes as the server's rendering in index.html. */}
            <article className="content-page">
                <header>
                    <h1>{title}</h1>
                    <Link to="/" className="link-btn"><Arrow direction="back" /> {t('policyPage.backHome', { appName: branding.name })}</Link>
                </header>
                {englishOnly && <p className={styles.notice}>{t('policyPage.englishOnly')}</p>}
                <div lang="en" dir="ltr">
                    <MarkdownRenderer content={fillPlaceholders(body, config)} showExpandButton={false} />
                </div>
            </article>
        </main>
    );
}

export function TermsPage() {
    const { t } = useTranslation();
    return <PolicyPage title={t('policyPage.termsTitle')} body={tosContent} />;
}

export function PrivacyPage() {
    const { t } = useTranslation();
    return <PolicyPage title={t('policyPage.privacyTitle')} body={ppContent} />;
}

export function AboutPage() {
    const { t } = useTranslation();
    return <PolicyPage title={t('policyPage.aboutTitle')} body={aboutContent} />;
}

export function FaqPage() {
    const { t } = useTranslation();
    return <PolicyPage title={t('policyPage.faqTitle')} body={faqContent} />;
}
