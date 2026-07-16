import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import {
    fetchMembers, fetchInvitations, inviteToBoard, revokeInvitation,
    setMemberRole, removeMember, leaveBoard, updateBoard, deleteBoard,
    type Board, type BoardMember, type PendingInvitation,
} from '../api';
import { getActiveLocale } from '../i18n/format';
import { MASCOTS } from '../mascots';
import { ConfirmDialog } from './ConfirmDialog';
import styles from './BoardSettingsModal.module.css';

interface BoardSettingsModalProps {
    open: boolean;
    board: Board | null;
    currentUserId: string | null;
    /** Whether the user has another board to fall back to; gates the delete action. */
    canDelete: boolean;
    /** Whether the user has a durable login method; ineligible (unclaimed) users can't invite. */
    canInvite: boolean;
    onClose: () => void;
    /** Called after a change that affects the board list (membership count, name, mascot). */
    onMembershipChanged: () => void;
    /** Called with the updated board after name/mascot are saved. */
    onBoardChanged: (board: Board) => void;
    /** Called after the current user leaves this board, so the shell can switch boards. */
    onLeft: () => void;
    /** Called after the board is deleted, so the shell can switch boards. */
    onDeleted: () => void;
}

type Confirm =
    | { kind: 'leave' }
    | { kind: 'delete' }
    | { kind: 'remove'; userId: string; name: string }
    | { kind: 'invite'; email: string };

function formatDate(iso: string): string {
    return new Date(iso).toLocaleDateString(getActiveLocale(), { year: 'numeric', month: 'short', day: 'numeric' });
}

export function BoardSettingsModal({
    open, board, currentUserId, canDelete, canInvite, onClose, onMembershipChanged, onBoardChanged, onLeft, onDeleted,
}: BoardSettingsModalProps) {
    const { t } = useTranslation();
    const [members, setMembers] = useState<BoardMember[]>([]);
    const [invitations, setInvitations] = useState<PendingInvitation[]>([]);
    const [loadedBoardId, setLoadedBoardId] = useState<string | null>(null);
    const [email, setEmail] = useState('');
    const [busy, setBusy] = useState(false);
    const [confirm, setConfirm] = useState<Confirm | null>(null);

    // Editable board fields, seeded from the board on the open-edge or when a different board is shown.
    const [name, setName] = useState('');
    const [mascot, setMascot] = useState('');
    const [seededId, setSeededId] = useState<string | null>(null);
    const [savingBoard, setSavingBoard] = useState(false);

    // Render-phase seed (the pattern used by BoardNameDialog/SettingsModal) avoids a setState-in-effect.
    if (open && board && board.id !== seededId) {
        setSeededId(board.id);
        setName(board.name);
        setMascot(board.mascot);
    } else if (!open && seededId !== null) {
        setSeededId(null);
    }

    const isOwner = board?.role === 'OWNER';
    // Derived (not a setState) so switching boards shows the spinner without a synchronous
    // setState-in-effect, and never flashes the previous board's members.
    const loading = !!board && loadedBoardId !== board.id;

    const reload = useCallback(async () => {
        if (!board) return;
        try {
            const [m, inv] = await Promise.all([
                fetchMembers(board.id),
                isOwner ? fetchInvitations(board.id) : Promise.resolve([] as PendingInvitation[]),
            ]);
            setMembers(m);
            setInvitations(inv);
            setLoadedBoardId(board.id);
        } catch (e) {
            console.error('Failed to load members', e);
        }
    }, [board, isOwner]);

    // Load members on open / board change; setState lives in the async continuation, not the effect body.
    useEffect(() => {
        if (!open || !board) return;
        let cancelled = false;
        Promise.all([
            fetchMembers(board.id),
            isOwner ? fetchInvitations(board.id) : Promise.resolve([] as PendingInvitation[]),
        ])
            .then(([m, inv]) => {
                if (cancelled) return;
                setMembers(m);
                setInvitations(inv);
                setLoadedBoardId(board.id);
            })
            .catch(e => console.error('Failed to load members', e));
        return () => { cancelled = true; };
    }, [open, board, isOwner]);

    useEffect(() => {
        if (!open) return;
        const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !confirm) onClose(); };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [open, onClose, confirm]);

    if (!open || !board) return null;

    const trimmedName = name.trim();
    const dirty = isOwner && (trimmedName !== board.name || mascot !== board.mascot);
    const canSaveBoard = dirty && trimmedName.length > 0 && trimmedName.length <= 60 && !savingBoard;

    const saveBoard = async () => {
        if (!canSaveBoard) return;
        setSavingBoard(true);
        try {
            const updated = await updateBoard(board.id, { name: trimmedName, mascot });
            onBoardChanged(updated);
            onClose();
        } catch (e) {
            console.error('Failed to save board', e); // surfaced via the global error toast
        } finally {
            // Always clear the flag — the modal stays mounted after onClose(), so leaving it set
            // would freeze the Save button on the next open.
            setSavingBoard(false);
        }
    };

    const submitInvite = (e: FormEvent) => {
        e.preventDefault();
        const trimmed = email.trim();
        if (trimmed) setConfirm({ kind: 'invite', email: trimmed });
    };

    const runConfirm = async () => {
        if (!confirm) return;
        setBusy(true);
        try {
            if (confirm.kind === 'invite') {
                await inviteToBoard(board.id, confirm.email);
                setEmail('');
                await reload();
                onMembershipChanged();
            } else if (confirm.kind === 'remove') {
                await removeMember(board.id, confirm.userId);
                await reload();
                onMembershipChanged();
            } else if (confirm.kind === 'leave') {
                await leaveBoard(board.id);
                onLeft();
            } else if (confirm.kind === 'delete') {
                await deleteBoard(board.id);
                onDeleted();
            }
        } catch (e) {
            console.error('Board action failed', e); // surfaced via the global error toast
        } finally {
            setBusy(false);
            setConfirm(null);
        }
    };

    const changeRole = async (userId: string, role: 'OWNER' | 'MEMBER') => {
        setBusy(true);
        try {
            await setMemberRole(board.id, userId, role);
            await reload();
        } catch (e) {
            console.error('Role change failed', e);
        } finally {
            setBusy(false);
        }
    };

    const revoke = async (id: string) => {
        setBusy(true);
        try {
            await revokeInvitation(board.id, id);
            await reload();
            onMembershipChanged();
        } catch (e) {
            console.error('Revoke failed', e);
        } finally {
            setBusy(false);
        }
    };

    const confirmCopy = (): { title: string; message: string; label: string; danger: boolean } => {
        switch (confirm?.kind) {
            case 'invite': return { title: t('boardSettingsModal.confirm.inviteTitle'), message: t('boardSettingsModal.consent'), label: t('boardSettingsModal.confirm.inviteLabel'), danger: false };
            case 'remove': return { title: t('boardSettingsModal.confirm.removeTitle'), message: t('boardSettingsModal.confirm.removeMessage', { name: confirm.name, board: board.name }), label: t('boardSettingsModal.remove'), danger: true };
            case 'leave': return { title: t('boardSettingsModal.confirm.leaveTitle'), message: t('boardSettingsModal.confirm.leaveMessage', { board: board.name }), label: t('boardSettingsModal.leaveBoard'), danger: true };
            case 'delete': return { title: t('boardSettingsModal.confirm.deleteTitle'), message: t('boardSettingsModal.confirm.deleteMessage', { board: board.name }), label: t('boardSettingsModal.deleteBoard'), danger: true };
            default: return { title: '', message: '', label: t('boardSettingsModal.confirm.defaultLabel'), danger: false };
        }
    };

    const onlyMember = members.length <= 1;

    return (
        <div className="modal-overlay modal-overlay--open" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
            <div className="modal" role="dialog" aria-modal="true" aria-labelledby="board-settings-title" style={{ width: 480 }}>
                <div className="modal__header">
                    <span className="modal__title" id="board-settings-title">{t('boardSettingsModal.title')}</span>
                    <button type="button" className="modal__close" onClick={onClose} aria-label={t('boardSettingsModal.close')}>✕</button>
                </div>

                <div className="modal__body">
                    {/* ── Name ── */}
                    <div className="field">
                        <label className="field__label" htmlFor="board-name">{t('boardSettingsModal.name')}</label>
                        <input
                            id="board-name"
                            type="text"
                            className="field__input"
                            value={name}
                            maxLength={60}
                            disabled={!isOwner || savingBoard}
                            onChange={e => setName(e.target.value)}
                            placeholder={t('boardSettingsModal.namePlaceholder')}
                        />
                    </div>

                    {/* ── Mascot ── */}
                    <div className="field">
                        <label className="field__label">{t('boardSettingsModal.mascot')}</label>
                        <p className={styles.fieldHint}>{t('boardSettingsModal.mascotHint')}</p>
                        <div className={styles.mascotGrid} role="radiogroup" aria-label={t('boardSettingsModal.mascotAria')}>
                            {MASCOTS.map(m => {
                                const selected = m.id === mascot;
                                return (
                                    <button
                                        key={m.id}
                                        type="button"
                                        role="radio"
                                        aria-checked={selected}
                                        disabled={!isOwner || savingBoard}
                                        className={`${styles.mascotCard} ${selected ? styles.mascotCardSelected : ''}`}
                                        onClick={() => setMascot(m.id)}
                                        title={m.label}
                                    >
                                        <img className={styles.mascotImg} src={m.url} alt="" aria-hidden />
                                        <span className={styles.mascotLabel}>{m.label}</span>
                                    </button>
                                );
                            })}
                        </div>
                    </div>

                    {/* ── Members ── */}
                    <div className="field">
                        <label className="field__label">{t('boardSettingsModal.members')}</label>
                        {loading ? (
                            <p className={styles.muted}>{t('boardSettingsModal.loading')}</p>
                        ) : (
                            <ul className={styles.list}>
                                {members.map(m => {
                                    const isSelf = m.userId === currentUserId;
                                    return (
                                        <li key={m.userId} className={styles.row}>
                                            <div className={styles.who}>
                                                <span className={styles.name}>
                                                    {m.displayName}{isSelf && <span className={styles.youTag}>{t('boardSettingsModal.youTag')}</span>}
                                                </span>
                                                <span className={styles.meta}>{t('boardSettingsModal.joinedOn', { date: formatDate(m.joinedAt) })}</span>
                                            </div>
                                            <span className={`${styles.roleChip} ${m.role === 'OWNER' ? styles.roleOwner : ''}`}>
                                                {m.role === 'OWNER' ? t('boardSettingsModal.roleOwner') : t('boardSettingsModal.roleMember')}
                                            </span>
                                            {isOwner && !isSelf && (
                                                <div className={styles.rowActions}>
                                                    <button
                                                        type="button"
                                                        className={styles.linkBtn}
                                                        disabled={busy}
                                                        onClick={() => changeRole(m.userId, m.role === 'OWNER' ? 'MEMBER' : 'OWNER')}
                                                    >
                                                        {m.role === 'OWNER' ? t('boardSettingsModal.makeMember') : t('boardSettingsModal.makeOwner')}
                                                    </button>
                                                    <button
                                                        type="button"
                                                        className={`${styles.linkBtn} ${styles.danger}`}
                                                        disabled={busy}
                                                        onClick={() => setConfirm({ kind: 'remove', userId: m.userId, name: m.displayName })}
                                                    >
                                                        {t('boardSettingsModal.remove')}
                                                    </button>
                                                </div>
                                            )}
                                        </li>
                                    );
                                })}
                            </ul>
                        )}

                        {isOwner && !canInvite && (
                            <p className={styles.inviteHint}>
                                {t('boardSettingsModal.inviteHint')}
                            </p>
                        )}

                        {isOwner && canInvite && (
                            <>
                                <form className={styles.inviteRow} onSubmit={submitInvite}>
                                    <input
                                        type="email"
                                        className={styles.inviteInput}
                                        placeholder={t('boardSettingsModal.invitePlaceholder')}
                                        value={email}
                                        onChange={e => setEmail(e.target.value)}
                                        disabled={busy}
                                        required
                                    />
                                    <button type="submit" className="btn btn--primary" disabled={busy || !email.trim()}>{t('boardSettingsModal.invite')}</button>
                                </form>

                                {invitations.length > 0 && (
                                    <div className={styles.pending}>
                                        <div className={styles.pendingHead}>{t('boardSettingsModal.pendingInvitations')}</div>
                                        {invitations.map(inv => (
                                            <div key={inv.id} className={styles.pendingRow}>
                                                <span className={styles.meta}>
                                                    {t('boardSettingsModal.invitedExpires', { invited: formatDate(inv.createdAt), expires: formatDate(inv.expiresAt) })}
                                                </span>
                                                <button type="button" className={`${styles.linkBtn} ${styles.danger}`} disabled={busy} onClick={() => revoke(inv.id)}>
                                                    {t('boardSettingsModal.revoke')}
                                                </button>
                                            </div>
                                        ))}
                                    </div>
                                )}
                            </>
                        )}
                    </div>

                    {/* ── Danger zone ── */}
                    <div className={styles.dangerZone}>
                        <button
                            type="button"
                            className="btn btn--ghost"
                            disabled={busy || onlyMember}
                            title={onlyMember ? t('boardSettingsModal.onlyMemberHint') : undefined}
                            onClick={() => setConfirm({ kind: 'leave' })}
                        >
                            {t('boardSettingsModal.leaveBoard')}
                        </button>
                        {isOwner && (
                            <button
                                type="button"
                                className="btn btn--danger"
                                disabled={busy || !canDelete}
                                title={!canDelete ? t('boardSettingsModal.onlyBoardHint') : undefined}
                                onClick={() => setConfirm({ kind: 'delete' })}
                            >
                                {t('boardSettingsModal.deleteBoard')}
                            </button>
                        )}
                    </div>
                </div>

                <div className="modal__footer">
                    <button type="button" className="btn btn--ghost" onClick={onClose} disabled={savingBoard}>
                        {dirty ? t('boardSettingsModal.cancel') : t('boardSettingsModal.done')}
                    </button>
                    {isOwner && (
                        <button type="button" className="btn btn--primary" onClick={saveBoard} disabled={!canSaveBoard}>
                            {savingBoard ? t('boardSettingsModal.saving') : t('boardSettingsModal.save')}
                        </button>
                    )}
                </div>
            </div>

            <ConfirmDialog
                open={confirm !== null}
                title={confirmCopy().title}
                message={confirmCopy().message}
                confirmLabel={confirmCopy().label}
                danger={confirmCopy().danger}
                onConfirm={runConfirm}
                onClose={() => setConfirm(null)}
            />
        </div>
    );
}
