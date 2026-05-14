import { useEffect, useRef, useState } from 'react';
import { API_ERROR_EVENT, type ApiErrorDetail } from '../api';
import styles from './ErrorToast.module.css';

interface Toast {
    id: number;
    message: string;
}

const AUTO_DISMISS_MS = 6000;

export function ErrorToastStack() {
    const [toasts, setToasts] = useState<Toast[]>([]);
    const nextId = useRef(1);

    useEffect(() => {
        const onError = (e: Event) => {
            const detail = (e as CustomEvent<ApiErrorDetail>).detail;
            if (!detail?.message) return;
            const id = nextId.current++;
            setToasts(prev => {
                // Collapse duplicates of the same message to avoid stacking floods.
                if (prev.some(t => t.message === detail.message)) return prev;
                return [...prev, { id, message: detail.message }];
            });
            window.setTimeout(() => {
                setToasts(prev => prev.filter(t => t.id !== id));
            }, AUTO_DISMISS_MS);
        };
        window.addEventListener(API_ERROR_EVENT, onError);
        return () => window.removeEventListener(API_ERROR_EVENT, onError);
    }, []);

    if (toasts.length === 0) return null;

    return (
        <div className={styles.stack} role="region" aria-label="Notifications">
            {toasts.map(t => (
                <div key={t.id} className={styles.toast} role="alert">
                    <span className={styles.icon} aria-hidden>!</span>
                    <span className={styles.message}>{t.message}</span>
                    <button
                        type="button"
                        className={styles.close}
                        onClick={() => setToasts(prev => prev.filter(p => p.id !== t.id))}
                        aria-label="Dismiss"
                    >
                        ×
                    </button>
                </div>
            ))}
        </div>
    );
}
