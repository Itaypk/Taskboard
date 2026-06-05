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

/**
 * Avatar button in the header that folds the low-frequency Settings and Sign-out actions into a
 * single overflow menu. Reads the signed-in identity from auth context for the avatar/photo;
 * the menu itself is right-aligned under the avatar and dismisses on outside-click or Escape.
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
  const initial = Array.from(primaryName)[0]?.toUpperCase() ?? '?';
  const photoUrl = user?.telegramPhotoUrl ?? null;

  const avatar = photoUrl
    ? <img className={styles.avatarImg} src={photoUrl} alt="" referrerPolicy="no-referrer" />
    : <span className={styles.avatarInitial} aria-hidden>{initial}</span>;

  const runAction = (action: () => void) => { setOpen(false); action(); };

  return (
    <div className={styles.wrap} ref={wrapRef}>
      <button
        type="button"
        className={styles.avatarBtn}
        onClick={() => setOpen(o => !o)}
        aria-label="Account menu"
        aria-haspopup="menu"
        aria-expanded={open}
      >
        {avatar}
      </button>

      {open && (
        <ul className={styles.menu} role="menu">
          <li className={styles.identity} role="none">
            <span className={styles.identityAvatar} aria-hidden>{avatar}</span>
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
