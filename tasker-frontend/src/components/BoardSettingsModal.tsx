import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import {
    fetchMembers, fetchInvitations, inviteToBoard, revokeInvitation,
    setMemberRole, removeMember, leaveBoard, updateBoard, deleteBoard,
    createCategory, updateCategory, deleteCategory, updateTag, deleteTag,
    type Board, type BoardMember, type PendingInvitation,
} from '../api';
import { getActiveLocale } from '../i18n/format';
import { MASCOTS } from '../mascots';
import { ConfirmDialog } from './ConfirmDialog';
import { CategoryEditor } from './CategoryEditor';
import { TagEditor } from './TagEditor';
import { Tabs } from './Tabs';
import type { Category, Tag, Task } from '../types';
import type { BoardSettingsTab } from '../taskLink';
import styles from './BoardSettingsModal.module.css';

interface BoardSettingsModalProps {
    open: boolean;
    board: Board | null;
    currentUserId: string | null;
    /** Whether the user has another board to fall back to; gates the delete action. */
    canDelete: boolean;
    /** Whether the user has a durable login method; ineligible (unclaimed) users can't invite. */
    canInvite: boolean;
    /** Tab to show; reset on every open (there is no deep-linked route for board settings). */
    initialTab?: BoardSettingsTab;
    /** Board categories/tags — owned by the board, editable by any member (not just the owner). */
    categories: Category[];
    tags: Tag[];
    /** Board tasks, used only to compute per-category usage counts for the Labels tab. */
    tasks: Task[];
    onClose: () => void;
    /** Called after a change that affects the board list (membership count, name, mascot). */
    onMembershipChanged: () => void;
    /** Called with the updated board after name/mascot are saved. */
    onBoardChanged: (board: Board) => void;
    /** Called with the final category list after categories were created/renamed/recolored/deleted. */
    onCategoriesChanged: (categories: Category[]) => void;
    /** Called after tags were renamed/recolored/deleted on save, so the shell refetches tags and tasks. */
    onTagsChanged: () => void;
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

const BOARD_TAB_IDS: readonly BoardSettingsTab[] = ['general', 'members', 'labels'];
const BOARD_TAB_LABEL_KEYS: Record<BoardSettingsTab, string> = {
    general: 'boardSettingsModal.tabs.general',
    members: 'boardSettingsModal.tabs.members',
    labels: 'boardSettingsModal.tabs.labels',
};

function formatDate(iso: string): string {
    return new Date(iso).toLocaleDateString(getActiveLocale(), { year: 'numeric', month: 'short', day: 'numeric' });
}

export function BoardSettingsModal({
    open, board, currentUserId, canDelete, canInvite, initialTab, categories, tags, tasks,
    onClose, onMembershipChanged, onBoardChanged, onCategoriesChanged, onTagsChanged, onLeft, onDeleted,
}: BoardSettingsModalProps) {
    const { t } = useTranslation();
    const boardTabs = BOARD_TAB_IDS.map(id => ({ id, label: t(BOARD_TAB_LABEL_KEYS[id]) }));
    const [activeTab, setActiveTab] = useState<BoardSettingsTab>(initialTab ?? 'general');
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

    // Staged category/tag edits (rename/recolor/delete/create), seeded from props on the open-edge
    // and persisted together with the board fields on Save.
    const [categoryDraft, setCategoryDraft] = useState<Category[]>(categories);
    const [tagDraft, setTagDraft] = useState<Tag[]>(tags);

    // Render-phase seed (the pattern used by BoardNameDialog/SettingsModal) avoids a setState-in-effect.
    if (open && board && board.id !== seededId) {
        setSeededId(board.id);
        setName(board.name);
        setMascot(board.mascot);
        setActiveTab(initialTab ?? 'general');
        setCategoryDraft(categories);
        setTagDraft(tags);
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

    // Category/tag diffs, computed once and shared between the dirty check and the save itself.
    // Any board member can edit labels (not just the owner) — see BoardMembershipService.requireMember.
    const categoryOriginalIds = new Set(categories.map(c => c.id));
    const categoryDraftIds = new Set(categoryDraft.map(c => c.id));
    const categoryDeletes = categories.filter(c => !categoryDraftIds.has(c.id));
    const categoryCreates = categoryDraft.filter(c => !categoryOriginalIds.has(c.id));
    const categoryUpdates = categoryDraft.filter(c => {
        const orig = categories.find(o => o.id === c.id);
        return orig && (orig.label !== c.label || orig.swatchId !== c.swatchId);
    });
    const tagDraftIds = new Set(tagDraft.map(t => t.id));
    const tagDeletes = tags.filter(t => !tagDraftIds.has(t.id));
    const tagUpdates = tagDraft.filter(t => {
        const orig = tags.find(o => o.id === t.id);
        return orig && t.label.trim() && (orig.label !== t.label.trim() || orig.colorId !== t.colorId);
    });
    const labelsDirty = categoryDeletes.length > 0 || categoryCreates.length > 0 || categoryUpdates.length > 0
        || tagDeletes.length > 0 || tagUpdates.length > 0;

    const usage = useMemo(() => {
        const counts: Record<string, number> = {};
        for (const task of tasks) counts[task.categoryId] = (counts[task.categoryId] ?? 0) + 1;
        return counts;
    }, [tasks]);

    if (!open || !board) return null;

    const trimmedName = name.trim();
    const boardFieldsDirty = isOwner && (trimmedName !== board.name || mascot !== board.mascot);
    const dirty = boardFieldsDirty || labelsDirty;
    const canSave = dirty && trimmedName.length > 0 && trimmedName.length <= 60 && !savingBoard;

    const handleSave = async () => {
        if (!canSave) return;
        setSavingBoard(true);
        try {
            const [updated] = await Promise.all([
                boardFieldsDirty ? updateBoard(board.id, { name: trimmedName, mascot }) : Promise.resolve(null),
                ...categoryDeletes.map(c => deleteCategory(board.id, c.id)),
                ...categoryUpdates.map(c => updateCategory(board.id, c.id, { label: c.label, swatchId: c.swatchId })),
                ...tagDeletes.map(t => deleteTag(board.id, t.id)),
                ...tagUpdates.map(t => updateTag(board.id, t.id, { label: t.label.trim(), colorId: t.colorId })),
            ]);
            const created = await Promise.all(
                categoryCreates.map(c => createCategory(board.id, { label: c.label, swatchId: c.swatchId }))
            );
            let createIdx = 0;
            const finalCategories = categoryDraft.map(c => !categoryOriginalIds.has(c.id) ? created[createIdx++] : c);

            if (updated) onBoardChanged(updated);
            if (categoryDeletes.length || categoryCreates.length || categoryUpdates.length) onCategoriesChanged(finalCategories);
            if (tagDeletes.length || tagUpdates.length) onTagsChanged();
            onClose();
        } catch (e) {
            console.error('Failed to save board settings', e); // surfaced via the global error toast
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
        <div className="modal-overlay modal-overlay--top modal-overlay--open" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
            <div className="modal" role="dialog" aria-modal="true" aria-labelledby="board-settings-title">
                <div className="modal__header">
                    <span className="modal__title" id="board-settings-title">{t('boardSettingsModal.title')}</span>
                    <button type="button" className="modal__close" onClick={onClose} aria-label={t('boardSettingsModal.close')}>✕</button>
                </div>

                <Tabs tabs={boardTabs} activeTab={activeTab} onChange={id => setActiveTab(id as BoardSettingsTab)} />

                <div className="modal__body">
                  {activeTab === 'general' && (
                    <>
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
                    </>
                  )}

                  {activeTab === 'members' && (
                    <div className="field">
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
                  )}

                  {activeTab === 'labels' && (
                    <>
                      <div className="field">
                          <label className="field__label">{t('boardSettingsModal.labels.categoriesLabel')}</label>
                          <p className="settings-hint">{t('boardSettingsModal.labels.categoriesHint')}</p>
                          <CategoryEditor
                              categories={categoryDraft}
                              usage={usage}
                              onChange={setCategoryDraft}
                          />
                      </div>

                      <div className="field">
                          <label className="field__label">{t('boardSettingsModal.labels.tagsLabel')}</label>
                          <p className="settings-hint">{t('boardSettingsModal.labels.tagsHint')}</p>
                          <TagEditor tags={tagDraft} onChange={setTagDraft} />
                      </div>
                    </>
                  )}
                </div>

                <div className="modal__footer">
                    <button type="button" className="btn btn--ghost" onClick={onClose} disabled={savingBoard}>
                        {dirty ? t('boardSettingsModal.cancel') : t('boardSettingsModal.done')}
                    </button>
                    <button type="button" className="btn btn--primary" onClick={handleSave} disabled={!canSave}>
                        {savingBoard ? t('boardSettingsModal.saving') : t('boardSettingsModal.save')}
                    </button>
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
