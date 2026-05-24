import { useEffect, useLayoutEffect, useRef, useState } from 'react';
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

  // Start hidden; useLayoutEffect measures and clamps before first paint.
  const [pos, setPos] = useState<{ top: number; left: number; visible: boolean }>(
    { top: y, left: x, visible: false }
  );

  useLayoutEffect(() => {
    if (!ref.current) return;
    const rect = ref.current.getBoundingClientRect();
    const pad = 8;
    let left = x;
    let top = y;
    if (left + rect.width  > window.innerWidth  - pad) left = window.innerWidth  - rect.width  - pad;
    if (top  + rect.height > window.innerHeight - pad) top  = window.innerHeight - rect.height - pad;
    if (left < pad) left = pad;
    if (top  < pad) top  = pad;
    setPos({ top, left, visible: true });
  }, [x, y]);

  const style: React.CSSProperties = {
    position: 'fixed',
    top: pos.top,
    left: pos.left,
    visibility: pos.visible ? 'visible' : 'hidden',
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
