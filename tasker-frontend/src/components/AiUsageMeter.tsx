import { useEffect, useState } from 'react';
import styles from './AiUsageMeter.module.css';
import type { AiUsage } from '../types';
import { fetchAiUsage } from '../api';

/**
 * AI budget meter for the settings dialog: how much of the tier's rolling-window token allowance
 * is left. Self-fetches on mount (only mounted while the Assistant tab is open). Unlimited tiers
 * collapse to a quiet badge — no bar to fill.
 */
export function AiUsageMeter() {
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
    return <p className="settings-hint">Couldn't load your AI usage right now.</p>;
  }

  if (!usage) {
    return <p className={styles.loading}>Loading usage…</p>;
  }

  if (usage.limitTokens == null) {
    return (
      <div className={styles.card}>
        <span className={styles.unlimitedBadge}>Unlimited</span>
        <p className={styles.unlimitedNote}>
          Your plan has no token cap. Use AI features as much as you like.
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
          <span className={styles.remainingLabel}>left</span>
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
        aria-label="AI tokens used this period"
      >
        <span className={styles.fill} style={{ width: `${pctUsed}%` }} />
      </div>

      <p className={styles.caption}>
        {Math.round(pctUsed)}% used · resets as your last{' '}
        {usage.windowDays} days roll forward
      </p>
    </div>
  );
}
