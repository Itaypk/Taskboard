import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import styles from './AppFooter.module.css';

/**
 * Rendered once below the router, so the legal/info pages are reachable from
 * everywhere — signed in or not. Links start at the inline edge because the
 * mascot occupies the opposite bottom corner.
 */
export default function AppFooter() {
    const { t } = useTranslation();
    return (
        <footer className={styles.footer}>
            <Link to="/about" className={styles.link}>{t('footer.about')}</Link>
            <Link to="/faq" className={styles.link}>{t('footer.faq')}</Link>
            <Link to="/terms" className={styles.link}>{t('footer.terms')}</Link>
            <Link to="/privacy" className={styles.link}>{t('footer.privacy')}</Link>
        </footer>
    );
}
