import { useEffect, useRef } from 'react';
import styles from './ImportResultDialog.module.css';
import { plural } from '../utils';
import { SUPPORT_EMAIL } from '../config';
import type { ImportErrorCategory, ImportResult } from './importResult';

interface ImportResultDialogProps {
  result: ImportResult | null;
  /** Called when the dialog is dismissed. On a successful import the caller reloads the app. */
  onClose: () => void;
}

const EMAIL_SKIP_MESSAGES: Record<string, string> = {
  ACCOUNT_HAS_EMAIL: 'Your account already has an email, so the one in the file was not imported.',
  TAKEN: 'The email in the file belongs to another account, so it was not imported. Your current email is unchanged.',
};

/** Plain-language copy per error category. `support` toggles the "Contact support" mailto link. */
const ERROR_COPY: Record<ImportErrorCategory, { title: string; body: string; support: boolean }> = {
  CORRUPTED_FILE: {
    title: 'This file couldn’t be imported',
    body: 'The file doesn’t look like a valid Backlog export. Make sure you’re importing a file you exported from Backlog, unchanged.',
    support: true,
  },
  UNSUPPORTED_VERSION: {
    title: 'Unsupported export version',
    body: 'This file was made by a different version of Backlog. Export your data again from this version, then import it.',
    support: true,
  },
  ACCOUNT_NOT_EMPTY: {
    title: 'Import needs an empty account',
    body: 'You can only import into an account that has no tasks of its own. Start with a fresh account, then import.',
    support: false,
  },
  INTERNAL_ERROR: {
    title: 'Something went wrong on our end',
    body: 'The import couldn’t be completed. Please try again in a moment.',
    support: true,
  },
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
  const errorCopy = result.kind === 'error' ? ERROR_COPY[result.category] : undefined;
  const title = success ? 'Import complete' : errorCopy!.title;
  const emailNote = success ? EMAIL_SKIP_MESSAGES[result.summary.emailSkipReason ?? ''] : undefined;

  return (
    <div
      className="modal-overlay modal-overlay--open"
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div
        className={`modal ${styles.dialog}`}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="import-result-title"
        aria-describedby="import-result-body"
      >
        <div className="modal__header">
          <span className="modal__title" id="import-result-title">{title}</span>
        </div>
        <div className={`modal__body ${styles.body}`} id="import-result-body">
          {success ? (
            <>
              <p className={styles.text}>
                Imported {plural(result.summary.tasks, 'task', 'tasks')},{' '}
                {plural(result.summary.tags, 'tag', 'tags')}, and{' '}
                {result.summary.categories === 1 ? '1 category' : `${result.summary.categories} categories`}.
              </p>
              {emailNote && (
                <p className={styles.note}>{emailNote}</p>
              )}
            </>
          ) : (
            <>
              <p className={styles.text}>{errorCopy!.body}</p>
              {result.detail && <p className={styles.detail}>{result.detail}</p>}
              {errorCopy!.support && (
                <p className={styles.note}>
                  Still stuck?{' '}
                  <a className="link-btn" href={`mailto:${SUPPORT_EMAIL}?subject=${encodeURIComponent('Backlog import problem')}`}>
                    Contact support
                  </a>
                </p>
              )}
            </>
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
