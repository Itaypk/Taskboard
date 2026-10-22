import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
    ApiError,
    fetchInboundMailAddress,
    revokeInboundMailAddress,
    rotateInboundMailAddress,
    type InboundMailAddress as InboundMailAddressData,
} from '../api';
import styles from './InboundMailAddress.module.css';

/**
 * The user's personal inbound email address: mail sent or forwarded there becomes a task.
 *
 * Unlike an API token the address can be shown again (it sits in contact lists and mail filters),
 * but it is still a secret: anyone who has it can add tasks. Hence a one-click rotate and turn-off,
 * each behind a confirmation, since the old address stops working at once.
 * Rendered only when the instance has inbound email on (`inboundMail` in the public config).
 */
export function InboundMailAddress() {
    const { t } = useTranslation();
    const [data, setData] = useState<InboundMailAddressData | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [copied, setCopied] = useState(false);

    useEffect(() => {
        let cancelled = false;
        fetchInboundMailAddress()
            .then(result => { if (!cancelled) setData(result); })
            .catch(e => console.error('Failed to load the inbound email address', e));
        return () => { cancelled = true; };
    }, []);

    const run = async (action: () => Promise<InboundMailAddressData>) => {
        setBusy(true);
        setError(null);
        setCopied(false);
        try {
            setData(await action());
        } catch (e) {
            if (!(e instanceof ApiError && e.status === 401)) {
                setError(t('inboundMail.errors.failed'));
            }
        } finally {
            setBusy(false);
        }
    };

    const handleCreate = () => run(rotateInboundMailAddress);

    const handleRotate = () => {
        if (!window.confirm(t('inboundMail.confirmRotate'))) return;
        void run(rotateInboundMailAddress);
    };

    const handleTurnOff = () => {
        if (!window.confirm(t('inboundMail.confirmTurnOff'))) return;
        void run(async () => {
            await revokeInboundMailAddress();
            return { address: null, createdAt: null };
        });
    };

    const handleCopy = async () => {
        if (!data?.address) return;
        try {
            await navigator.clipboard.writeText(data.address);
            setCopied(true);
        } catch {
            // Clipboard access can be denied; the address is selectable on screen either way.
            setCopied(false);
        }
    };

    return (
        <div className="field">
            <label className="field__label">{t('inboundMail.label')}</label>
            <p className="settings-hint">{t('inboundMail.hint')}</p>

            {data?.address ? (
                <>
                    <code className={styles.address}>{data.address}</code>
                    <div className={styles.actions}>
                        <button type="button" className="btn btn--ghost" onClick={handleCopy} disabled={busy}>
                            {copied ? t('inboundMail.copied') : t('inboundMail.copy')}
                        </button>
                        <button type="button" className="btn btn--ghost" onClick={handleRotate} disabled={busy}>
                            {t('inboundMail.rotate')}
                        </button>
                        <button type="button" className="btn btn--ghost" onClick={handleTurnOff} disabled={busy}>
                            {t('inboundMail.turnOff')}
                        </button>
                    </div>
                    <p className="settings-hint">{t('inboundMail.secretHint')}</p>
                </>
            ) : (
                data && (
                    <button type="button" className="btn btn--ghost" onClick={handleCreate} disabled={busy}>
                        {t('inboundMail.create')}
                    </button>
                )
            )}

            {error && <p className="settings-error">{error}</p>}
        </div>
    );
}
