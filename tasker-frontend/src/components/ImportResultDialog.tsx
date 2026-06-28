import { useEffect, useRef } from 'react';
import type { ImportSummary } from '../api';

export type ImportResult =
  | { kind: 'success'; summary: ImportSummary }
  | { kind: 'error'; message: string };

interface ImportResultDialogProps {
  result: ImportResult | null;
  /** Called when the dialog is dismissed. On a successful import the caller reloads the app. */
  onClose: () => void;
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

const EMAIL_SKIP_MESSAGES: Record<string, string> = {
  ACCOUNT_HAS_EMAIL: 'Your account already has an email, so the one in the file was not imported.',
  TAKEN: 'The email in the file belongs to another account, so it was not imported. Your current email is unchanged.',
};

export function ImportResultDialog({ result, onClose }: ImportResultDialogProps) {
  const buttonRef = useRef<HTMLButtonElement>(null);
  const open = result !== null;

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    if (open) {
      window.addEventListener('keydown', handler);
      buttonRef.current?.focus();
    }
    return () => window.removeEventListener('keydown', handler);
  }, [open, onClose]);

  if (!result) return null;

  const success = result.kind === 'success';
  const title = success ? 'Import complete' : 'Import failed';
  const emailNote = success ? EMAIL_SKIP_MESSAGES[result.summary.emailSkipReason ?? ''] : undefined;

  return (
    <div
      className="modal-overlay modal-overlay--open"
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div
        className="modal"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="import-result-title"
        aria-describedby="import-result-body"
        style={{ width: 380 }}
      >
        <div className="modal__header">
          <span className="modal__title" id="import-result-title">{title}</span>
        </div>
        <div className="modal__body" style={{ gap: 8, paddingBottom: 8 }} id="import-result-body">
          {success ? (
            <>
              <p style={{ margin: 0, fontSize: 14, lineHeight: 1.55, color: 'var(--ink-soft)' }}>
                Imported {plural(result.summary.tasks, 'task', 'tasks')},{' '}
                {plural(result.summary.tags, 'tag', 'tags')}, and{' '}
                {result.summary.categories === 1 ? '1 category' : `${result.summary.categories} categories`}.
              </p>
              {emailNote && (
                <p style={{ margin: 0, fontSize: 13, lineHeight: 1.5, color: 'var(--ink-soft)' }}>
                  {emailNote}
                </p>
              )}
            </>
          ) : (
            <p style={{ margin: 0, fontSize: 14, lineHeight: 1.55, color: 'var(--ink-soft)' }}>
              {result.message}
            </p>
          )}
        </div>
        <div className="modal__footer">
          <button
            ref={buttonRef}
            type="button"
            className="btn btn--primary"
            onClick={onClose}
          >
            {success ? 'Reload' : 'Close'}
          </button>
        </div>
      </div>
    </div>
  );
}
