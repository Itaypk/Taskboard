import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import styles from './NotFoundPage.module.css';
import { useBranding } from './publicConfig';

export function NotFoundPage() {
  const { t } = useTranslation();
  const { name: appName } = useBranding();
  return (
    <div className={styles.wrap}>
      <div className={styles.card}>
        <p className={styles.code}>404</p>
        <h1 className={styles.heading}>{t('notFound.heading')}</h1>
        <p className={styles.body}>
          {t('notFound.body')}
        </p>
        <Link to="/" className={styles.homeLink}>{t('notFound.homeLink', { appName })}</Link>
      </div>
    </div>
  );
}
