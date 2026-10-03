import { Link } from 'react-router-dom';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import MarkdownRenderer from '../components/MarkdownRenderer';
import { useBranding } from '../publicConfig';
import tosContent from './tos.md?raw';
import ppContent from './privacy-policy.md?raw';
import aboutContent from './about.md?raw';
import faqContent from './faq.md?raw';
import styles from './PolicyPage.module.css';
import { Arrow } from '../components/Arrow';

/** Fill the instance's contact addresses (from `/api/public/config`) into the raw policy markdown. */
function fillPlaceholders(md: string, branding: { supportEmail: string; abuseEmail: string }): string {
    return md.replaceAll('{{SUPPORT_EMAIL}}', branding.supportEmail).replaceAll('{{ABUSE_EMAIL}}', branding.abuseEmail);
}

interface PolicyPageProps {
    title: string;
    body: string;
}

function PolicyPage({ title, body }: PolicyPageProps) {
    const { t } = useTranslation();
    const branding = useBranding();
    useEffect(() => {
        const previous = document.title;
        document.title = `${title} — ${branding.name}`;
        return () => { document.title = previous; };
    }, [title, branding.name]);

    return (
        <main className="board-wrap">
            <article className={styles.page}>
                <header className={styles.header}>
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
