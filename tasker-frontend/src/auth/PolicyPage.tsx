import { Link } from 'react-router-dom';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import MarkdownRenderer from '../components/MarkdownRenderer';
import { useBranding } from '../publicConfig';
import tosContent from './tos.md?raw';
import ppContent from './privacy-policy.md?raw';
import aboutContent from './about.md?raw';
import faqContent from './faq.md?raw';
import { Arrow } from '../components/Arrow';

/**
 * Fill the instance's name, URL and contact addresses into the raw policy markdown. Same placeholders
 * as the server's `ContentPageRenderer`, which renders these pages for clients without JavaScript; the
 * SPA is always served from the instance's own origin, so that stands in for its base URL.
 */
function fillPlaceholders(md: string, branding: { name: string; supportEmail: string; abuseEmail: string }): string {
    return md
        .replaceAll('{{APP_NAME}}', branding.name)
        .replaceAll('{{APP_URL}}', window.location.origin)
        .replaceAll('{{SUPPORT_EMAIL}}', branding.supportEmail)
        .replaceAll('{{ABUSE_EMAIL}}', branding.abuseEmail);
}

/**
 * The document title of every other route — mirrors the default `<title>` in `index.html`. Restored
 * explicitly rather than captured on mount: on a direct visit the server already titled the document
 * after this page.
 */
function homeTitle(appName: string): string {
    return `${appName} - your personal tasks planner`;
}

interface PolicyPageProps {
    title: string;
    body: string;
}

function PolicyPage({ title, body }: PolicyPageProps) {
    const { t } = useTranslation();
    const branding = useBranding();
    useEffect(() => {
        document.title = `${title} — ${branding.name}`;
        return () => { document.title = homeTitle(branding.name); };
    }, [title, branding.name]);

    return (
        <main className="board-wrap">
            {/* Same markup and global classes as the server's rendering in index.html. */}
            <article className="content-page">
                <header>
                    <h1>{title}</h1>
                    <Link to="/" className="link-btn"><Arrow direction="back" /> {t('policyPage.backHome', { appName: branding.name })}</Link>
                </header>
                <MarkdownRenderer content={fillPlaceholders(body, branding)} showExpandButton={false} />
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
