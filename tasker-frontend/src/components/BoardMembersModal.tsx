import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
    fetchMembers, fetchInvitations, inviteToBoard, revokeInvitation,
    setMemberRole, removeMember, leaveBoard,
    type Board, type BoardMember, type PendingInvitation,
} from '../api';
import { ConfirmDialog } from './ConfirmDialog';
import styles from './BoardMembersModal.module.css';

interface BoardMembersModalProps {
    open: boolean;
    board: Board | null;
    currentUserId: string | null;
    onClose: () => void;
    /** Called after a change that affects the board list (membership count, or the user leaving). */
    onMembershipChanged: () => void;
    /** Called after the current user leaves this board, so the shell can switch boards. */
    onLeft: () => void;
}

type Confirm =
    | { kind: 'leave' }
    | { kind: 'remove'; userId: string; name: string }
    | { kind: 'invite'; email: string };

const CONSENT =
    'Everyone you add can see and edit every task on this board — including ones already there — ' +
    'until you remove them. They will also see your name. Send the invitation?';

function formatDate(iso: string): string {
    return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

export function BoardMembersModal({
    open, board, currentUserId, onClose, onMembershipChanged, onLeft,
}: BoardMembersModalProps) {
    const [members, setMembers] = useState<BoardMember[]>([]);
    const [invitations, setInvitations] = useState<PendingInvitation[]>([]);
    const [loadedBoardId, setLoadedBoardId] = useState<string | null>(null);
    const [email, setEmail] = useState('');
    const [busy, setBusy] = useState(false);
    const [confirm, setConfirm] = useState<Confirm | null>(null);

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

    // Load on open / board change; setState lives in the async continuation, not the effect body.
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
        const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [open, onClose]);

    if (!open || !board) return null;

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
            }
        } catch (e) {
            console.error('Member action failed', e); // surfaced via the global error toast
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
            default: return { title: '', message: '', label: 'Confirm', danger: false };
        }
    };

    return (
        <div className="modal-overlay modal-overlay--open" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
            <div className="modal" role="dialog" aria-modal="true" aria-labelledby="members-title" style={{ width: 460 }}>
                <div className="modal__header">
                    <span className="modal__title" id="members-title">Members · {board.name}</span>
                    <button type="button" className="modal__close" onClick={onClose} aria-label="Close">✕</button>
                </div>

                <div className="modal__body">
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

                    {isOwner && (
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

                <div className="modal__footer">
                    <button
                        type="button"
                        className="btn btn--ghost"
                        disabled={busy || members.length <= 1}
                        title={members.length <= 1 ? 'You are the only member — delete the board instead' : undefined}
                        onClick={() => setConfirm({ kind: 'leave' })}
                    >
                        Leave board
                    </button>
                    <button type="button" className="btn btn--primary" onClick={onClose}>Done</button>
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
