import { Link } from 'react-router-dom';
import { useEffect } from 'react';
import MarkdownRenderer from '../components/MarkdownRenderer';
import tosContent from './tos.md?raw';
import ppContent from './privacy-policy.md?raw';
import styles from './PolicyPage.module.css';

interface PolicyPageProps {
    title: string;
    body: string;
}

function PolicyPage({ title, body }: PolicyPageProps) {
    useEffect(() => {
        const previous = document.title;
        document.title = `${title} — Backlog.fyi`;
        return () => { document.title = previous; };
    }, [title]);

    return (
        <main className="board-wrap">
            <article className={styles.page}>
                <header className={styles.header}>
                    <h1>{title}</h1>
                    <Link to="/" className="link-btn">← Back to Backlog.fyi</Link>
                </header>
                <MarkdownRenderer content={body} showExpandButton={false} />
            </article>
        </main>
    );
}

export function TermsPage() {
    return <PolicyPage title="Terms of Service" body={tosContent} />;
}

export function PrivacyPage() {
    return <PolicyPage title="Privacy Policy" body={ppContent} />;
}
