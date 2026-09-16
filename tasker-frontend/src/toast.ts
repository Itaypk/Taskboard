/**
 * The app-wide toast channel. Kept separate from `api.ts` (which owns the API-error half of the
 * same stack, `API_ERROR_EVENT`) so UI code can raise a toast without pulling in the transport
 * layer. `components/ToastStack` is the single listener and the single fixed layer on screen —
 * anything bottom-anchored and transient belongs here rather than in its own fixed element, or it
 * ends up overlapping the others on a phone.
 */

export const TOAST_EVENT = 'app:toast';

export type ToastKind = 'error' | 'success' | 'info';

export interface ToastAction {
    label: string;
    /** Runs on click; the toast dismisses itself first. */
    onClick: () => void;
}

export interface ToastRequest {
    message: string;
    /** Drives the colour and the ARIA role (`error` is an assertive alert). Defaults to `info`. */
    kind?: ToastKind;
    action?: ToastAction;
    /** Auto-dismiss delay; `0` keeps the toast up until it is dismissed or superseded. */
    durationMs?: number;
    /**
     * Toasts sharing a key replace one another instead of stacking — a burst of completions leaves
     * one undo offer, not five. Unkeyed toasts collapse by identical message (an error flood).
     */
    key?: string;
}

export function notifyToast(toast: ToastRequest): void {
    window.dispatchEvent(new CustomEvent<ToastRequest>(TOAST_EVENT, { detail: toast }));
}
