import { useEffect, useRef } from 'react';
import styles from './ContextMenu.module.css';

export interface ContextMenuAction {
  label: string;
  danger?: boolean;
  onClick: () => void;
}

interface ContextMenuProps {
  x: number;
  y: number;
  actions: ContextMenuAction[];
  onClose: () => void;
}

export function ContextMenu({ x, y, actions, onClose }: ContextMenuProps) {
  const ref = useRef<HTMLUListElement>(null);

  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    const handleClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    window.addEventListener('keydown', handleKey);
    window.addEventListener('mousedown', handleClick);
    return () => {
      window.removeEventListener('keydown', handleKey);
      window.removeEventListener('mousedown', handleClick);
    };
  }, [onClose]);

  // Clamp to viewport so menu never goes off-screen
  const style: React.CSSProperties = {
    position: 'fixed',
    top: y,
    left: x,
  };

  return (
    <ul ref={ref} className={styles.menu} style={style} role="menu">
      {actions.map((action, i) => (
        <li key={i} role="none">
          <button
            type="button"
            role="menuitem"
            className={`${styles.item} ${action.danger ? styles.itemDanger : ''}`}
            onClick={() => { action.onClick(); onClose(); }}
          >
            {action.label}
          </button>
        </li>
      ))}
    </ul>
  );
}
