import { useRef, useState, type ChangeEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { deleteAccount, exportAccount, importAccount, type ImportSummary } from '../../api';
import { ImportResultDialog } from '../ImportResultDialog';
import { categorizeImportError, type ImportResult } from '../importResult';
import styles from './SettingsSections.module.css';

interface Props {
  onAccountDeleted: () => void;
  /** Lets the dialog's Escape key cancel a pending delete confirmation instead of closing. */
  onDeleteConfirmChange: (pending: boolean) => void;
  deleteConfirm: boolean;
}

/** Export, import, and deleting the account. */
export function DataSection({ onAccountDeleted, deleteConfirm, onDeleteConfirmChange }: Props) {
  const { t } = useTranslation();
  const [deleting, setDeleting] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importResult, setImportResult] = useState<ImportResult | null>(null);
  const importInputRef = useRef<HTMLInputElement | null>(null);
  const busy = exporting || deleting || importing;

  const handleExport = async () => {
    setExporting(true);
    try {
      await exportAccount();
    } catch (e) {
      console.error('Export failed', e);
    } finally {
      setExporting(false);
    }
  };

  const handleImportClick = () => {
    setImportResult(null);
    importInputRef.current?.click();
  };

  const handleImportFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    // Always clear the input so picking the same file twice still triggers onChange.
    e.target.value = '';
    if (!file) return;
    setImporting(true);
    setImportResult(null);
    try {
      const text = await file.text();
      let payload: unknown;
      try {
        payload = JSON.parse(text);
      } catch {
        // We can't even parse the file locally — it's a corrupted/unsupported file.
        setImportResult({ kind: 'error', category: 'CORRUPTED_FILE', detail: 'The file is not valid JSON.' });
        return;
      }
      const summary: ImportSummary = await importAccount(payload);
      setImportResult({ kind: 'success', summary });
    } catch (err: unknown) {
      console.error('Import failed', err);
      setImportResult({ kind: 'error', ...categorizeImportError(err) });
    } finally {
      setImporting(false);
    }
  };

  const handleImportResultClose = () => {
    const wasSuccess = importResult?.kind === 'success';
    setImportResult(null);
    // Hard reload so the rest of the app re-fetches against the freshly populated account.
    if (wasSuccess) window.location.reload();
  };

  const handleDeleteConfirmed = async () => {
    setDeleting(true);
    try {
      await deleteAccount();
      onAccountDeleted();
    } catch (e) {
      console.error('Account deletion failed', e);
      setDeleting(false);
      onDeleteConfirmChange(false);
    }
  };

  return (
    <>
      <div className="field">
        <label className="field__label">{t('settingsModal.data.exportImport')}</label>
        <p className="settings-hint">{t('settingsModal.data.exportImportHint')}</p>
        <div className={styles.inlineRow}>
          <button type="button" className="btn btn--ghost" onClick={handleExport} disabled={busy}>
            {exporting ? t('settingsModal.general.exporting') : t('settingsModal.general.exportData')}
          </button>
          <button type="button" className="btn btn--ghost" onClick={handleImportClick} disabled={busy}>
            {importing ? t('settingsModal.general.importing') : t('settingsModal.general.importData')}
          </button>
          <input
            ref={importInputRef}
            type="file"
            accept="application/json"
            style={{ display: 'none' }}
            onChange={handleImportFile}
          />
        </div>
      </div>

      <div className="danger-zone">
        <p className="danger-zone__label">{t('settingsModal.general.dangerZone')}</p>
        <div className="danger-zone__actions">
          {deleteConfirm ? (
            <div className="danger-zone__confirm">
              <span className="danger-zone__confirm-text">{t('settingsModal.general.deleteWarning')}</span>
              <button type="button" className="btn btn--danger-solid" onClick={handleDeleteConfirmed} disabled={deleting}>
                {deleting ? t('settingsModal.general.deleting') : t('settingsModal.general.confirmDelete')}
              </button>
              <button type="button" className="btn btn--ghost" onClick={() => onDeleteConfirmChange(false)} disabled={deleting}>
                {t('settingsModal.general.cancel')}
              </button>
            </div>
          ) : (
            <button type="button" className="btn btn--danger" onClick={() => onDeleteConfirmChange(true)} disabled={busy}>
              {t('settingsModal.general.deleteAccount')}
            </button>
          )}
        </div>
      </div>
      <ImportResultDialog result={importResult} onClose={handleImportResultClose} />
    </>
  );
}
