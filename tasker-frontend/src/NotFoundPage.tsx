import { Link } from 'react-router-dom';
import styles from './NotFoundPage.module.css';

export function NotFoundPage() {
  return (
    <div className={styles.wrap}>
      <div className={styles.card}>
        <p className={styles.code}>404</p>
        <h1 className={styles.heading}>Page not found</h1>
        <p className={styles.body}>
          This page doesn't exist. It may have been moved or the URL is incorrect.
        </p>
        <Link to="/" className={styles.homeLink}>Go to Backlog.fyi</Link>
      </div>
    </div>
  );
}
