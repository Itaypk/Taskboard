import { useTranslation } from 'react-i18next';
import styles from './UpdateBanner.module.css';

/**
 * Shown when the sync poll detects the backend version changed mid-session (a redeploy). The open tab
 * is running stale JS, so we nudge — but don't force — a reload.
 */
export function UpdateBanner({ onReload }: { onReload: () => void }) {
  const { t } = useTranslation();
  return (
    <div className={styles.banner} role="status">
      <span>{t('updateBanner.message')}</span>
      <button type="button" className={styles.reloadBtn} onClick={onReload}>{t('updateBanner.refresh')}</button>
    </div>
  );
}
