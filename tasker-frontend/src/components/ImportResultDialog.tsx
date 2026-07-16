import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import styles from './ImportResultDialog.module.css';
import { SUPPORT_EMAIL } from '../config';
import type { ImportErrorCategory, ImportResult } from './importResult';

interface ImportResultDialogProps {
  result: ImportResult | null;
  /** Called when the dialog is dismissed. On a successful import the caller reloads the app. */
  onClose: () => void;
}

const EMAIL_SKIP_MESSAGE_KEYS: Record<string, string> = {
  ACCOUNT_HAS_EMAIL: 'importResultDialog.emailSkip.accountHasEmail',
  TAKEN: 'importResultDialog.emailSkip.taken',
};

/** Translation-key pairs per error category. `support` toggles the "Contact support" mailto link. */
const ERROR_COPY_KEYS: Record<ImportErrorCategory, { title: string; body: string; support: boolean }> = {
  CORRUPTED_FILE: {
    title: 'importResultDialog.errors.corruptedFile.title',
    body: 'importResultDialog.errors.corruptedFile.body',
    support: true,
  },
  UNSUPPORTED_VERSION: {
    title: 'importResultDialog.errors.unsupportedVersion.title',
    body: 'importResultDialog.errors.unsupportedVersion.body',
    support: true,
  },
  ACCOUNT_NOT_EMPTY: {
    title: 'importResultDialog.errors.accountNotEmpty.title',
    body: 'importResultDialog.errors.accountNotEmpty.body',
    support: false,
  },
  INTERNAL_ERROR: {
    title: 'importResultDialog.errors.internalError.title',
    body: 'importResultDialog.errors.internalError.body',
    support: true,
  },
};

export function ImportResultDialog({ result, onClose }: ImportResultDialogProps) {
  const { t } = useTranslation();
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
  const errorCopyKeys = result.kind === 'error' ? ERROR_COPY_KEYS[result.category] : undefined;
  const title = success ? t('importResultDialog.successTitle') : t(errorCopyKeys!.title);
  const emailSkipKey = success ? EMAIL_SKIP_MESSAGE_KEYS[result.summary.emailSkipReason ?? ''] : undefined;
  const emailNote = emailSkipKey ? t(emailSkipKey) : undefined;

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
                {t('importResultDialog.imported', {
                  tasks: t('importResultDialog.tasksCount', { count: result.summary.tasks }),
                  tags: t('importResultDialog.tagsCount', { count: result.summary.tags }),
                  categories: t('importResultDialog.categoriesCount', { count: result.summary.categories }),
                })}
              </p>
              {emailNote && (
                <p className={styles.note}>{emailNote}</p>
              )}
            </>
          ) : (
            <>
              <p className={styles.text}>{t(errorCopyKeys!.body)}</p>
              {result.detail && <p className={styles.detail}>{result.detail}</p>}
              {errorCopyKeys!.support && (
                <p className={styles.note}>
                  {t('importResultDialog.stillStuck')}{' '}
                  {/* Support inbox reads this subject line — kept English per the admin-facing-output convention (docs/I18N.md). */}
                  <a className="link-btn" href={`mailto:${SUPPORT_EMAIL}?subject=${encodeURIComponent('Backlog import problem')}`}>
                    {t('importResultDialog.contactSupport')}
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
            {success ? t('importResultDialog.reload') : t('importResultDialog.close')}
          </button>
        </div>
      </div>
    </div>
  );
}
