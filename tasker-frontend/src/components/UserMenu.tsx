import { useEffect, useRef, useState } from 'react';
import { useAuth } from '../auth/AuthContext';
import styles from './UserMenu.module.css';

interface UserMenuProps {
  /** User-chosen display name (from settings); falls back to Telegram identity when absent. */
  displayName?: string | null;
  onOpenStats: () => void;
  onOpenSettings: () => void;
  onSignOut: () => void;
}

/** Sliders glyph for the menu trigger — matches the line-art icon style of the header's plan button. */
function SettingsIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M3 4h12" />
      <circle cx="7" cy="4" r="1.5" />
      <path d="M3 9h12" />
      <circle cx="11" cy="9" r="1.5" />
      <path d="M3 14h12" />
      <circle cx="6" cy="14" r="1.5" />
    </svg>
  );
}

/**
 * Settings (sliders) button in the header that folds the low-frequency Stats, Settings and Sign-out
 * actions into a single menu. Reads the signed-in identity from auth context for the menu header;
 * the menu itself is right-aligned under the trigger and dismisses on outside-click or Escape.
 */
export function UserMenu({ displayName, onOpenStats, onOpenSettings, onSignOut }: UserMenuProps) {
  const { state } = useAuth();
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    const onClick = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    window.addEventListener('mousedown', onClick);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('mousedown', onClick);
    };
  }, [open]);

  const user = state.status === 'authenticated' ? state.user : null;
  const primaryName =
    displayName?.trim() || user?.telegramFirstName || user?.telegramUsername || user?.email || 'Account';
  const secondary = user?.telegramUsername ? `@${user.telegramUsername}` : user?.email ?? null;

  const runAction = (action: () => void) => { setOpen(false); action(); };

  return (
    <div className={styles.wrap} ref={wrapRef}>
      <button
        type="button"
        className={`icon-btn ${styles.trigger}`}
        onClick={() => setOpen(o => !o)}
        aria-label="Account menu"
        aria-haspopup="menu"
        aria-expanded={open}
      >
        <SettingsIcon />
      </button>

      {open && (
        <ul className={styles.menu} role="menu">
          <li className={styles.identity} role="none">
            <span className={styles.identityText}>
              <span className={styles.identityName}>{primaryName}</span>
              {secondary && <span className={styles.identitySecondary}>{secondary}</span>}
            </span>
          </li>
          <li className={styles.divider} role="separator" />
          <li role="none">
            <button
              type="button"
              role="menuitem"
              className={styles.item}
              onClick={() => runAction(onOpenStats)}
            >
              Stats
            </button>
          </li>
          <li role="none">
            <button
              type="button"
              role="menuitem"
              className={styles.item}
              onClick={() => runAction(onOpenSettings)}
            >
              Settings
            </button>
          </li>
          <li role="none">
            <button
              type="button"
              role="menuitem"
              className={`${styles.item} ${styles.itemDanger}`}
              onClick={() => runAction(onSignOut)}
            >
              Sign out
            </button>
          </li>
        </ul>
      )}
    </div>
  );
}
