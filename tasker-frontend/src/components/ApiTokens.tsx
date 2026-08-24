import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
    createApiToken,
    fetchApiTokens,
    revokeApiToken,
    type ApiToken,
    type ApiTokenScope,
} from '../api';
import { ApiError } from '../api';
import styles from './ApiTokens.module.css';

const MAX_TOKENS = 5;

function formatDate(iso: string | null): string | null {
    if (!iso) return null;
    const parsed = new Date(iso);
    return Number.isNaN(parsed.getTime()) ? null : parsed.toLocaleDateString();
}

/**
 * API tokens for the agent API (`/api/external/v1`), used by external AI assistants and scripts.
 *
 * The plaintext secret exists only in the create response, so it is held in component state and
 * shown until dismissed — there is no way to retrieve it again, and the copy affordance plus the
 * explicit warning are the whole reason this section is worth a UI rather than a DB insert.
 */
export function ApiTokens() {
    const { t } = useTranslation();
    const [tokens, setTokens] = useState<ApiToken[] | null>(null);
    const [name, setName] = useState('');
    const [scope, setScope] = useState<ApiTokenScope>('write');
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [freshSecret, setFreshSecret] = useState<string | null>(null);
    const [copied, setCopied] = useState(false);

    useEffect(() => {
        let cancelled = false;
        fetchApiTokens()
            .then(list => { if (!cancelled) setTokens(list); })
            .catch(e => console.error('Failed to load API tokens', e));
        return () => { cancelled = true; };
    }, []);

    const atLimit = (tokens?.length ?? 0) >= MAX_TOKENS;

    const handleCreate = async () => {
        if (!name.trim() || busy) return;
        setBusy(true);
        setError(null);
        setCopied(false);
        try {
            const created = await createApiToken(name.trim(), scope);
            setFreshSecret(created.token);
            setTokens(current => [created.apiToken, ...(current ?? [])]);
            setName('');
        } catch (e) {
            if (e instanceof ApiError && e.status === 409) {
                setError(t('apiTokens.errors.limit', { max: MAX_TOKENS }));
            } else if (!(e instanceof ApiError && e.status === 401)) {
                setError(t('apiTokens.errors.createFailed'));
            }
        } finally {
            setBusy(false);
        }
    };

    const handleRevoke = async (token: ApiToken) => {
        if (!window.confirm(t('apiTokens.confirmRevoke', { name: token.name }))) return;
        setBusy(true);
        setError(null);
        try {
            await revokeApiToken(token.id);
            setTokens(current => (current ?? []).filter(item => item.id !== token.id));
        } catch (e) {
            if (!(e instanceof ApiError && e.status === 401)) {
                setError(t('apiTokens.errors.revokeFailed'));
            }
        } finally {
            setBusy(false);
        }
    };

    const handleCopy = async () => {
        if (!freshSecret) return;
        try {
            await navigator.clipboard.writeText(freshSecret);
            setCopied(true);
        } catch {
            // Clipboard access can be denied; the secret is selectable on screen either way.
            setCopied(false);
        }
    };

    return (
        <div className="field">
            <label className="field__label">{t('apiTokens.label')}</label>
            <p className="settings-hint">{t('apiTokens.hint')}</p>

            {freshSecret && (
                <div className={styles.reveal}>
                    <p className={styles.revealWarning}>{t('apiTokens.copyNow')}</p>
                    <code className={styles.secret}>{freshSecret}</code>
                    <div className={styles.revealActions}>
                        <button type="button" className="btn btn--ghost" onClick={handleCopy}>
                            {copied ? t('apiTokens.copied') : t('apiTokens.copy')}
                        </button>
                        <button
                            type="button"
                            className="btn btn--ghost"
                            onClick={() => { setFreshSecret(null); setCopied(false); }}
                        >
                            {t('apiTokens.dismiss')}
                        </button>
                    </div>
                </div>
            )}

            <ul className={styles.list}>
                {tokens?.map(token => (
                    <li key={token.id} className={styles.token}>
                        <div className={styles.tokenMeta}>
                            <span className={styles.tokenName}>{token.name}</span>
                            <span className={styles.tokenDetail}>
                                <code>{token.prefix}…</code>
                                {' · '}
                                {t(`apiTokens.scopes.${token.scope}`)}
                                {' · '}
                                {formatDate(token.lastUsedAt)
                                    ? t('apiTokens.lastUsed', { date: formatDate(token.lastUsedAt) })
                                    : t('apiTokens.neverUsed')}
                            </span>
                        </div>
                        <button
                            type="button"
                            className="btn btn--ghost"
                            onClick={() => handleRevoke(token)}
                            disabled={busy}
                        >
                            {t('apiTokens.revoke')}
                        </button>
                    </li>
                ))}
                {tokens?.length === 0 && <li className="settings-hint">{t('apiTokens.none')}</li>}
            </ul>

            <div className={styles.createRow}>
                <input
                    type="text"
                    className={styles.nameInput}
                    value={name}
                    maxLength={100}
                    placeholder={t('apiTokens.namePlaceholder')}
                    onChange={event => setName(event.target.value)}
                    disabled={busy || atLimit}
                />
                <select
                    className={styles.scopeSelect}
                    value={scope}
                    onChange={event => setScope(event.target.value as ApiTokenScope)}
                    disabled={busy || atLimit}
                    aria-label={t('apiTokens.scopeLabel')}
                >
                    <option value="write">{t('apiTokens.scopes.write')}</option>
                    <option value="read">{t('apiTokens.scopes.read')}</option>
                </select>
                <button
                    type="button"
                    className="btn btn--ghost"
                    onClick={handleCreate}
                    disabled={busy || atLimit || !name.trim()}
                    title={atLimit ? t('apiTokens.errors.limit', { max: MAX_TOKENS }) : undefined}
                >
                    {t('apiTokens.create')}
                </button>
            </div>

            <p className="settings-hint">{t('apiTokens.docsHint')}</p>

            {error && <p className="settings-error">{error}</p>}
        </div>
    );
}
