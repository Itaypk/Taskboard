import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './AiUsageMeter.module.css';
import type { AiUsage } from '../types';
import { fetchAiUsage } from '../api';

/**
 * AI budget meter for the settings dialog: how much of the tier's rolling-window token allowance
 * is left. Self-fetches on mount (only mounted while the Assistant tab is open). Unlimited tiers
 * collapse to a quiet badge — no bar to fill. An account whose tier doesn't grant access
 * (`grantsAccess: false`) shows a quiet note instead — there is no self-serve way to request one.
 */
export function AiUsageMeter() {
  const { t } = useTranslation();
  const [usage, setUsage] = useState<AiUsage | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let alive = true;
    fetchAiUsage()
      .then(u => { if (alive) setUsage(u); })
      .catch(() => { if (alive) setError(true); });
    return () => { alive = false; };
  }, []);

  if (error) {
    return <p className="settings-hint">{t('aiUsageMeter.loadError')}</p>;
  }

  if (!usage) {
    return <p className={styles.loading}>{t('aiUsageMeter.loading')}</p>;
  }

  // grantsAccess is the authoritative bit from the backend — an unrecognized tier string falls
  // back there too, so this never mistakenly renders a 0%-left budget bar for it.
  if (!usage.grantsAccess) {
    return (
      <div className={styles.card}>
        <p className={styles.unlimitedNote}>{t('aiUsageMeter.noAccessNote')}</p>
      </div>
    );
  }

  if (usage.limitTokens == null) {
    return (
      <div className={styles.card}>
        <span className={styles.unlimitedBadge}>{t('aiUsageMeter.unlimited')}</span>
        <p className={styles.unlimitedNote}>
          {t('aiUsageMeter.unlimitedNote')}
        </p>
      </div>
    );
  }

  const used = Math.min(usage.usedTokens, usage.limitTokens);
  const pctUsed = usage.limitTokens === 0 ? 100 : (used / usage.limitTokens) * 100;
  const pctRemaining = Math.max(100 - pctUsed, 0);
  // Drain the bar warmer as the budget runs low — amber past 75%, coral past 90%.
  const level = pctUsed >= 90 ? 'critical' : pctUsed >= 75 ? 'low' : 'ok';

  return (
    <div className={styles.card}>
      <div className={styles.head}>
        <div className={styles.headline}>
          <span className={styles.remaining}>{Math.round(pctRemaining)}%</span>
          <span className={styles.remainingLabel}>{t('aiUsageMeter.left')}</span>
        </div>
        <span className={styles.tier}>{usage.tier}</span>
      </div>

      <div
        className={styles.track}
        data-level={level}
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={usage.limitTokens}
        aria-valuenow={used}
        aria-label={t('aiUsageMeter.ariaLabel')}
      >
        <span className={styles.fill} style={{ width: `${pctUsed}%` }} />
      </div>

      <p className={styles.caption}>
        {t('aiUsageMeter.caption', { count: usage.windowDays, pct: Math.round(pctUsed) })}
      </p>
    </div>
  );
}
