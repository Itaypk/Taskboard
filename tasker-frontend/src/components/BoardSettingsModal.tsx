import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
    fetchMembers, fetchInvitations, inviteToBoard, revokeInvitation,
    setMemberRole, removeMember, leaveBoard, updateBoard, deleteBoard,
    type Board, type BoardMember, type PendingInvitation,
} from '../api';
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

const CONSENT =
    'Everyone you add can see and edit every task on this board — including ones already there — ' +
    'until you remove them. They will also see your name. Send the invitation?';

function formatDate(iso: string): string {
    return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

export function BoardSettingsModal({
    open, board, currentUserId, canDelete, canInvite, onClose, onMembershipChanged, onBoardChanged, onLeft, onDeleted,
}: BoardSettingsModalProps) {
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
            case 'invite': return { title: 'Share this board?', message: CONSENT, label: 'Send invitation', danger: false };
            case 'remove': return { title: 'Remove member?', message: `Remove ${confirm.name} from "${board.name}"? Their tasks stay on the board.`, label: 'Remove', danger: true };
            case 'leave': return { title: 'Leave this board?', message: `Leave "${board.name}"? Tasks you added stay for the other members. You can only rejoin via a new invitation.`, label: 'Leave board', danger: true };
            case 'delete': return { title: 'Delete this board?', message: `Delete "${board.name}" and all of its tasks, categories, and tags? This cannot be undone.`, label: 'Delete board', danger: true };
            default: return { title: '', message: '', label: 'Confirm', danger: false };
        }
    };

    const onlyMember = members.length <= 1;

    return (
        <div className="modal-overlay modal-overlay--open" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
            <div className="modal" role="dialog" aria-modal="true" aria-labelledby="board-settings-title" style={{ width: 480 }}>
                <div className="modal__header">
                    <span className="modal__title" id="board-settings-title">Board settings</span>
                    <button type="button" className="modal__close" onClick={onClose} aria-label="Close">✕</button>
                </div>

                <div className="modal__body">
                    {/* ── Name ── */}
                    <div className="field">
                        <label className="field__label" htmlFor="board-name">Name</label>
                        <input
                            id="board-name"
                            type="text"
                            className="field__input"
                            value={name}
                            maxLength={60}
                            disabled={!isOwner || savingBoard}
                            onChange={e => setName(e.target.value)}
                            placeholder="Board name"
                        />
                    </div>

                    {/* ── Mascot ── */}
                    <div className="field">
                        <label className="field__label">Mascot</label>
                        <p className={styles.fieldHint}>The little character in the corner of this board.</p>
                        <div className={styles.mascotGrid} role="radiogroup" aria-label="Board mascot">
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
                        <label className="field__label">Members</label>
                        {loading ? (
                            <p className={styles.muted}>Loading…</p>
                        ) : (
                            <ul className={styles.list}>
                                {members.map(m => {
                                    const isSelf = m.userId === currentUserId;
                                    return (
                                        <li key={m.userId} className={styles.row}>
                                            <div className={styles.who}>
                                                <span className={styles.name}>
                                                    {m.displayName}{isSelf && <span className={styles.youTag}> (you)</span>}
                                                </span>
                                                <span className={styles.meta}>joined {formatDate(m.joinedAt)}</span>
                                            </div>
                                            <span className={`${styles.roleChip} ${m.role === 'OWNER' ? styles.roleOwner : ''}`}>
                                                {m.role === 'OWNER' ? 'Owner' : 'Member'}
                                            </span>
                                            {isOwner && !isSelf && (
                                                <div className={styles.rowActions}>
                                                    <button
                                                        type="button"
                                                        className={styles.linkBtn}
                                                        disabled={busy}
                                                        onClick={() => changeRole(m.userId, m.role === 'OWNER' ? 'MEMBER' : 'OWNER')}
                                                    >
                                                        {m.role === 'OWNER' ? 'Make member' : 'Make owner'}
                                                    </button>
                                                    <button
                                                        type="button"
                                                        className={`${styles.linkBtn} ${styles.danger}`}
                                                        disabled={busy}
                                                        onClick={() => setConfirm({ kind: 'remove', userId: m.userId, name: m.displayName })}
                                                    >
                                                        Remove
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
                                Link a login method (Telegram or a verified email) in Settings to invite people to this board.
                            </p>
                        )}

                        {isOwner && canInvite && (
                            <>
                                <form className={styles.inviteRow} onSubmit={submitInvite}>
                                    <input
                                        type="email"
                                        className={styles.inviteInput}
                                        placeholder="invite by email…"
                                        value={email}
                                        onChange={e => setEmail(e.target.value)}
                                        disabled={busy}
                                        required
                                    />
                                    <button type="submit" className="btn btn--primary" disabled={busy || !email.trim()}>Invite</button>
                                </form>

                                {invitations.length > 0 && (
                                    <div className={styles.pending}>
                                        <div className={styles.pendingHead}>Pending invitations</div>
                                        {invitations.map(inv => (
                                            <div key={inv.id} className={styles.pendingRow}>
                                                <span className={styles.meta}>
                                                    invited {formatDate(inv.createdAt)} · expires {formatDate(inv.expiresAt)}
                                                </span>
                                                <button type="button" className={`${styles.linkBtn} ${styles.danger}`} disabled={busy} onClick={() => revoke(inv.id)}>
                                                    Revoke
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
                            title={onlyMember ? 'You are the only member — delete the board instead' : undefined}
                            onClick={() => setConfirm({ kind: 'leave' })}
                        >
                            Leave board
                        </button>
                        {isOwner && (
                            <button
                                type="button"
                                className="btn btn--danger"
                                disabled={busy || !canDelete}
                                title={!canDelete ? 'This is your only board — create another first' : undefined}
                                onClick={() => setConfirm({ kind: 'delete' })}
                            >
                                Delete board
                            </button>
                        )}
                    </div>
                </div>

                <div className="modal__footer">
                    <button type="button" className="btn btn--ghost" onClick={onClose} disabled={savingBoard}>
                        {dirty ? 'Cancel' : 'Done'}
                    </button>
                    {isOwner && (
                        <button type="button" className="btn btn--primary" onClick={saveBoard} disabled={!canSaveBoard}>
                            {savingBoard ? 'Saving…' : 'Save'}
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
