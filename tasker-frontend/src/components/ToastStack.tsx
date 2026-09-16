import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { API_ERROR_EVENT, type ApiErrorDetail } from '../api';
import { TOAST_EVENT, type ToastAction, type ToastKind, type ToastRequest } from '../toast';
import styles from './ToastStack.module.css';

interface Toast {
    id: number;
    kind: ToastKind;
    message: string;
    action?: ToastAction;
    key?: string;
}

const DEFAULT_DISMISS_MS = 6000;

const ICONS: Record<ToastKind, string> = { error: '!', success: '✓', info: 'i' };

/**
 * The app's single toast layer: API errors (via `API_ERROR_EVENT`), action confirmations with an
 * undo offer, and the "new version available" nudge all render here. One fixed container means one
 * set of responsive rules, and nothing bottom-anchored can overlap anything else.
 */
export function ToastStack() {
    const { t } = useTranslation();
    const [toasts, setToasts] = useState<Toast[]>([]);
    const nextId = useRef(1);
    const timers = useRef(new Map<number, number>());

    const dismiss = useCallback((id: number) => {
        const timer = timers.current.get(id);
        if (timer !== undefined) {
            window.clearTimeout(timer);
            timers.current.delete(id);
        }
        setToasts(prev => prev.filter(toast => toast.id !== id));
    }, []);

    const show = useCallback((request: ToastRequest) => {
        const id = nextId.current++;
        const toast: Toast = {
            id,
            kind: request.kind ?? 'info',
            message: request.message,
            action: request.action,
            key: request.key,
        };
        setToasts(prev => {
            if (request.key) return [...prev.filter(p => p.key !== request.key), toast];
            // Collapse duplicates of the same message to avoid stacking floods.
            if (prev.some(p => p.message === request.message)) return prev;
            return [...prev, toast];
        });
        const duration = request.durationMs ?? DEFAULT_DISMISS_MS;
        // A collapsed duplicate never entered the list, so this timer simply finds nothing to drop.
        if (duration > 0) timers.current.set(id, window.setTimeout(() => dismiss(id), duration));
    }, [dismiss]);

    useEffect(() => {
        const onToast = (e: Event) => {
            const detail = (e as CustomEvent<ToastRequest>).detail;
            if (detail?.message) show(detail);
        };
        const onError = (e: Event) => {
            const detail = (e as CustomEvent<ApiErrorDetail>).detail;
            if (detail?.message) show({ kind: 'error', message: detail.message });
        };
        window.addEventListener(TOAST_EVENT, onToast);
        window.addEventListener(API_ERROR_EVENT, onError);
        return () => {
            window.removeEventListener(TOAST_EVENT, onToast);
            window.removeEventListener(API_ERROR_EVENT, onError);
        };
    }, [show]);

    // Timers outlive a fast unmount (StrictMode's double mount, a route change) otherwise.
    useEffect(() => {
        const pending = timers.current;
        return () => {
            pending.forEach(timer => window.clearTimeout(timer));
            pending.clear();
        };
    }, []);

    if (toasts.length === 0) return null;

    return (
        <div className={styles.stack} role="region" aria-label={t('toast.region')}>
            {toasts.map(({ id, kind, message, action }) => (
                <div
                    key={id}
                    className={`${styles.toast} ${styles[kind]}`}
                    role={kind === 'error' ? 'alert' : 'status'}
                >
                    <span className={styles.icon} aria-hidden>{ICONS[kind]}</span>
                    <span className={styles.message}>{message}</span>
                    {action && (
                        <button
                            type="button"
                            className={styles.action}
                            onClick={() => { dismiss(id); action.onClick(); }}
                        >
                            {action.label}
                        </button>
                    )}
                    <button
                        type="button"
                        className={styles.close}
                        onClick={() => dismiss(id)}
                        aria-label={t('toast.dismiss')}
                    >
                        ×
                    </button>
                </div>
            ))}
        </div>
    );
}
