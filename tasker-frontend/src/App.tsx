import { useState, useMemo, useCallback, useEffect, useRef } from 'react';
import {
  DndContext,
  MouseSensor,
  TouchSensor,
  useSensor,
  useSensors,
  closestCenter,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  arrayMove,
  rectSortingStrategy,
} from '@dnd-kit/sortable';
import { PostItNote, type AssigneeChipInfo } from './components/PostItNote';
import { TaskDrawer } from './components/TaskDrawer';
import { SettingsModal } from './components/SettingsModal';
import { BoardFilter } from './components/BoardFilter';
import { BrandBoard } from './components/BrandBoard';
import { BoardNameDialog } from './components/BoardNameDialog';
import { WeeklyPlanDrawer } from './components/WeeklyPlanDrawer';
import { ContextMenu, type ContextMenuAction } from './components/ContextMenu';
import { ConfirmDialog } from './components/ConfirmDialog';
import { ScheduleTaskModal } from './components/ScheduleTaskModal';
import { UserMenu } from './components/UserMenu';
import { StatsModal } from './components/StatsModal';
import { BoardMembersModal } from './components/BoardMembersModal';
import { DEFAULT_SETTINGS } from './data';
import { fetchBoards, createBoard, renameBoard, deleteBoard, fetchTasks, fetchCategories, fetchUserSettings, fetchTags, fetchCurrentPlan, checkTaskChanges, createTask, updateTask, deleteTask, reorderTask, removeTaskFromPlan, clearTutorialTasks, addTaskToPlan, fetchMembers, setTaskAssignee, type TaskStatusFilter, type Board, type BoardMember } from './api';
import type { Task, UserSettings, Tag, CurrentPlan, TaskFilter } from './types';

const ACTIVE_BOARD_KEY = 'backlog.activeBoardId';
import { Routes, Route } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { LoginPage } from './auth/LoginPage';
import { TermsPage, PrivacyPage } from './auth/PolicyPage';
import { EmailLoginConfirmPage } from './auth/EmailLoginConfirmPage';
import { EmailVerifyConfirmPage } from './auth/EmailVerifyConfirmPage';
import { InvitePage } from './auth/InvitePage';
import { NotFoundPage } from './NotFoundPage';
import pineappleUrl from './assets/pineapple.png';
import './App.css';

/** Up to two initials from a display name (e.g. "Dana Scully" → "DS", "it***@gmail.com" → "IT"). */
function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function emptyMessageFor(filter: TaskFilter): string {
  switch (filter) {
    case 'todo': return 'Nothing pinned up. Add your first task.';
    case 'plan': return "Nothing in this week's plan yet.";
    case 'done': return 'No completed tasks yet.';
    case 'all':  return 'No tasks yet.';
  }
}

function PlanIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <rect x="3" y="3.5" width="12" height="11" rx="1.5" />
      <path d="M3 6.5h12" />
      <path d="M6 2.5v2M12 2.5v2" />
      <path d="M6 9.5h6M6 12h4" />
    </svg>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/terms" element={<TermsPage />} />
      <Route path="/privacy" element={<PrivacyPage />} />
      <Route path="/email-login" element={<EmailLoginConfirmPage />} />
      <Route path="/email-verify" element={<EmailVerifyConfirmPage />} />
      <Route path="/invite" element={<InvitePage />} />
      <Route path="/" element={<AuthShell />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}

function AuthShell() {
  const { state, signOut } = useAuth();

  if (state.status === 'loading') {
    return <div className="board-wrap"><div className="board board--empty">Loading…</div></div>;
  }

  if (state.status === 'unauthenticated') {
    return <LoginPage />;
  }

  return <Board onSignOut={signOut} />;
}

function Board({ onSignOut }: { onSignOut: () => Promise<void> }) {
  const { state: authState } = useAuth();
  const currentUserId = authState.status === 'authenticated' ? authState.user.id : null;
  // Unclaimed = zero-registration account with no login identity yet; nudge them to save it.
  const claimed = authState.status === 'authenticated' ? authState.user.claimed : true;
  const [nudgeDismissed, setNudgeDismissed] = useState(() => localStorage.getItem('saveAccountNudgeDismissed') === '1');
  // One active board at a time; every account has at least one (the backend lists them default-first).
  const [boards, setBoards]           = useState<Board[]>([]);
  const [activeBoardId, setActiveBoardId] = useState<string | null>(null);
  const [boardDialog, setBoardDialog] = useState<{ mode: 'create' | 'rename' } | null>(null);
  const [boardDeleteConfirm, setBoardDeleteConfirm] = useState(false);
  const [tasks, setTasks]             = useState<Task[]>([]);
  const [tags, setTags]               = useState<Tag[]>([]);
  const [settings, setSettings]       = useState<UserSettings>({ ...DEFAULT_SETTINGS, categories: [] });
  const [selectedId, setSelectedId]   = useState<string | null>(null);
  const [isCreating, setIsCreating]   = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [statsOpen, setStatsOpen] = useState(false);
  const [membersOpen, setMembersOpen] = useState(false);
  // Keyed to its board so a stale fetch from a previous board is ignored without a synchronous reset.
  const [memberData, setMemberData]   = useState<{ boardId: string; members: BoardMember[] } | null>(null);
  const [leavingId, setLeavingId]     = useState<string | null>(null);
  const [loading, setLoading]         = useState(true);
  const [error, setError]             = useState<string | null>(null);
  const [draggingId, setDraggingId]   = useState<string | null>(null);
  const [filter, setFilter]           = useState<TaskFilter>('todo');
  const [showArchived, setShowArchived] = useState(false);
  const [archivedTasks, setArchivedTasks] = useState<Task[]>([]);
  const [currentPlan, setCurrentPlan]  = useState<CurrentPlan | null>(null);
  const [planDrawerOpen, setPlanDrawerOpen] = useState(false);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; taskId: string; inPlan: boolean } | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<{ taskId: string; title: string } | null>(null);
  const [scheduleModal, setScheduleModal] = useState<{ taskId: string; title: string } | null>(null);
  const lastSyncedAt = useRef(new Date().toISOString());

  // Mouse: start drag after 5px to keep clicks alive.
  // Touch: long-press (~200ms) so tap-to-open and finger-scroll still work.
  const sensors = useSensors(
    useSensor(MouseSensor, { activationConstraint: { distance: 5 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 200, tolerance: 5 } }),
  );

  // User-scoped bootstrap: the board list plus settings and the (per-user) current plan. Picks the
  // active board from the last-used one in localStorage, falling back to the default board.
  useEffect(() => {
    (async () => {
      try {
        const [loadedBoards, loadedSettings, loadedPlan] = await Promise.all([
          fetchBoards(), fetchUserSettings(), fetchCurrentPlan(),
        ]);
        setBoards(loadedBoards);
        setCurrentPlan(loadedPlan);
        setSettings(prev => ({
          ...prev,
          ...loadedSettings,
          displayName: loadedSettings.displayName ?? prev.displayName,
          contextBlock: loadedSettings.contextBlock ?? prev.contextBlock,
          categories: prev.categories,
        }));
        const stored = localStorage.getItem(ACTIVE_BOARD_KEY);
        setActiveBoardId(loadedBoards.find(b => b.id === stored)?.id ?? loadedBoards[0].id);
      } catch {
        setError('Failed to load data. Is the backend running?');
        setLoading(false);
      }
    })();
  }, []);

  // Board-scoped reference data (categories live on the board, tags too) reloads on each switch.
  useEffect(() => {
    if (!activeBoardId) return;
    let cancelled = false;
    Promise.all([fetchCategories(activeBoardId), fetchTags(activeBoardId)])
      .then(([loadedCategories, loadedTags]) => {
        if (cancelled) return;
        setSettings(prev => ({ ...prev, categories: loadedCategories }));
        setTags(loadedTags);
      })
      .catch(e => console.error('Failed to load board reference data', e));
    return () => { cancelled = true; };
  }, [activeBoardId]);

  // Members back the assignee chips/picker and claim actions; only fetched for shared boards
  // (>1 member) so single-member boards make no extra request and look exactly like before.
  // The solo-board case sets no state — `members` derives to empty below.
  useEffect(() => {
    if (!activeBoardId) return;
    const board = boards.find(b => b.id === activeBoardId);
    if (!board || board.memberCount <= 1) return;
    let cancelled = false;
    fetchMembers(activeBoardId)
      .then(m => { if (!cancelled) setMemberData({ boardId: activeBoardId, members: m }); })
      .catch(e => console.error('Failed to load members', e));
    return () => { cancelled = true; };
  }, [activeBoardId, boards]);

  // Tasks reload when the board or the status filter changes. 'plan' reuses the open-tasks fetch
  // and applies plan-membership client-side. This also clears the initial loading flag.
  const fetchStatus: TaskStatusFilter = filter === 'plan' ? 'todo' : filter;
  useEffect(() => {
    if (!activeBoardId) return;
    let cancelled = false;
    fetchTasks(activeBoardId, fetchStatus)
      .then(loadedTasks => { if (!cancelled) setTasks(loadedTasks); })
      .catch(e => console.error('Failed to load tasks', e))
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [activeBoardId, fetchStatus]);

  const drawerOpen   = selectedId !== null || isCreating;

  // Periodic sync — check for task changes every 60 s; skip while tab is hidden or drawer is open.
  // On visibility restore, run an immediate catch-up sync.
  useEffect(() => {
    if (loading || !activeBoardId) return;

    const sync = async () => {
      if (drawerOpen || document.hidden) return;
      try {
        const { hasChanges, checkedAt } = await checkTaskChanges(activeBoardId, lastSyncedAt.current);
        lastSyncedAt.current = checkedAt;
        const [freshPlan, freshTags] = await Promise.all([fetchCurrentPlan(), fetchTags(activeBoardId)]);
        setCurrentPlan(freshPlan);
        setTags(freshTags);
        if (hasChanges) {
          const freshTasks = await fetchTasks(activeBoardId, fetchStatus);
          setTasks(freshTasks);
        }
      } catch { /* silent — don't surface background network blips */ }
    };

    const id = setInterval(sync, 60_000);
    document.addEventListener('visibilitychange', sync);
    return () => {
      clearInterval(id);
      document.removeEventListener('visibilitychange', sync);
    };
  }, [loading, drawerOpen, fetchStatus, activeBoardId]);
  const selectedTask = tasks.find(t => t.id === selectedId) ?? null;

  const sortedTasks = useMemo(
    () => [...tasks].sort((a, b) => a.sortKey < b.sortKey ? -1 : a.sortKey > b.sortKey ? 1 : 0),
    [tasks],
  );

  const planId = currentPlan?.id ?? null;
  const sortedArchivedTasks = useMemo(
    () => [...archivedTasks].sort((a, b) => a.sortKey < b.sortKey ? -1 : a.sortKey > b.sortKey ? 1 : 0),
    [archivedTasks],
  );
  const visibleTasks = useMemo(() => {
    const base = sortedTasks.filter(t => {
      if (t.id === leavingId) return true;
      switch (filter) {
        case 'todo': return t.status === 'todo';
        case 'done': return t.status === 'done';
        case 'all':  return t.status !== 'archived';
        case 'plan': return t.status === 'todo' && planId !== null && t.lastScheduledInSessionId === planId;
      }
    });
    return filter === 'all' && showArchived ? [...base, ...sortedArchivedTasks] : base;
  }, [sortedTasks, sortedArchivedTasks, leavingId, filter, planId, showArchived]);

  const visibleIds = useMemo(() => visibleTasks.map(t => t.id), [visibleTasks]);

  const hasTutorialTasks = useMemo(() => tasks.some(t => t.tutorial), [tasks]);

  const categoryById = useMemo(
    () => new Map(settings.categories.map(c => [c.id, c])),
    [settings.categories],
  );

  const closeDrawer = () => { setSelectedId(null); setIsCreating(false); };

  const handleSave = async (updated: Omit<Task, 'sortKey'>) => {
    if (!activeBoardId) return;
    try {
      const { id: _id, createdAt: _ca, ...payload } = updated;
      if (isCreating) {
        const created = await createTask(activeBoardId, payload);
        setTasks(prev => [...prev, created]);
      } else {
        const saved = await updateTask(activeBoardId, updated.id, payload);
        setTasks(prev => prev.map(t => t.id === saved.id ? saved : t));
      }

      // Refetch tags in case a new tag was created
      fetchTags(activeBoardId).then(setTags).catch(e => console.error('Failed to refetch tags', e));

      closeDrawer();
    } catch (e) {
      console.error('Failed to save task', e);
    }
  };

  const handleDelete = useCallback(async (id: string) => {
    if (!activeBoardId) return;
    try {
      await deleteTask(activeBoardId, id);
      setTasks(prev => prev.filter(t => t.id !== id));
    } catch (e) {
      console.error('Failed to delete task', e);
    }
  }, [activeBoardId]);

  const handleClearTutorial = useCallback(async () => {
    if (!activeBoardId) return;
    try {
      await clearTutorialTasks(activeBoardId);
      setTasks(prev => prev.filter(t => !t.tutorial));
    } catch (e) {
      console.error('Failed to clear tutorial tasks', e);
    }
  }, [activeBoardId]);

  const handleMarkDone = useCallback((id: string) => {
    const task = tasks.find(t => t.id === id);
    if (!task || !activeBoardId) return;
    setLeavingId(id);
    const { id: _id, createdAt: _ca, sortKey: _sk, ...payload } = task;
    updateTask(activeBoardId, id, { ...payload, status: 'done' }).then(() => {
      setTimeout(() => {
        setTasks(prev => prev.map(t => t.id === id ? { ...t, status: 'done' } : t));
        setLeavingId(null);
      }, 380);
    }).catch(e => {
      console.error('Failed to mark task done', e);
      setLeavingId(null);
    });
  }, [tasks, activeBoardId]);

  const handleMarkTodo = useCallback((id: string) => {
    const task = tasks.find(t => t.id === id) ?? archivedTasks.find(t => t.id === id);
    if (!task || !activeBoardId) return;
    const { id: _id, createdAt: _ca, sortKey: _sk, ...payload } = task;
    updateTask(activeBoardId, id, { ...payload, status: 'todo' }).then(() => {
      setTasks(prev => {
        const existing = prev.find(t => t.id === id);
        return existing
          ? prev.map(t => t.id === id ? { ...t, status: 'todo' } : t)
          : [...prev, { ...task, status: 'todo' }];
      });
      setArchivedTasks(prev => prev.filter(t => t.id !== id));
    }).catch(e => {
      console.error('Failed to mark task todo', e);
    });
  }, [tasks, archivedTasks, activeBoardId]);

  const handleRemoveFromPlan = useCallback(async (id: string) => {
    if (!activeBoardId) return;
    try {
      await removeTaskFromPlan(activeBoardId, id);
      setTasks(prev => prev.map(t => t.id === id ? { ...t, lastScheduledInSessionId: null } : t));
      setCurrentPlan(prev => prev ? { ...prev, tasks: prev.tasks.filter(t => t.id !== id) } : prev);
    } catch (e) {
      console.error('Failed to remove task from plan', e);
    }
  }, [activeBoardId]);

  const handleAddToPlan = useCallback(async (taskId: string, startIso: string, endIso: string) => {
    if (!currentPlan || !activeBoardId) return;
    try {
      await addTaskToPlan(taskId, startIso, endIso);
      const [freshPlan, freshTasks] = await Promise.all([fetchCurrentPlan(), fetchTasks(activeBoardId, fetchStatus)]);
      setCurrentPlan(freshPlan);
      setTasks(freshTasks);
    } catch (e) {
      console.error('Failed to add task to plan', e);
    }
  }, [currentPlan, fetchStatus, activeBoardId]);

  const requestDelete = useCallback((id: string) => {
    const task = tasks.find(t => t.id === id) ?? archivedTasks.find(t => t.id === id);
    if (!task) return;
    setDeleteConfirm({ taskId: id, title: task.title });
  }, [tasks, archivedTasks]);

  // Empty unless the keyed fetch matches the active board (ignores a stale prior-board fetch).
  const members = useMemo(
    () => (memberData?.boardId === activeBoardId ? memberData.members : []),
    [memberData, activeBoardId],
  );
  const membersById = useMemo(() => new Map(members.map(m => [m.userId, m])), [members]);
  const sharedBoard = members.length > 1;

  // Resolved chip info for a task's assignee on shared boards; null when unassigned or solo board.
  const resolveAssignee = useCallback((task: Task): AssigneeChipInfo | null => {
    if (!sharedBoard || !task.assigneeUserId) return null;
    const member = membersById.get(task.assigneeUserId);
    const name = member?.displayName ?? 'Member';
    return { initials: initialsOf(name), name, isMe: task.assigneeUserId === currentUserId };
  }, [sharedBoard, membersById, currentUserId]);

  const handleSetAssignee = useCallback(async (taskId: string, userId: string | null) => {
    if (!activeBoardId) return;
    setTasks(prev => prev.map(t => t.id === taskId ? { ...t, assigneeUserId: userId } : t));
    try {
      await setTaskAssignee(activeBoardId, taskId, userId);
    } catch (e) {
      console.error('Failed to set assignee', e);
      fetchTasks(activeBoardId, fetchStatus).then(setTasks).catch(() => {});
    }
  }, [activeBoardId, fetchStatus]);

  const buildContextMenuActions = useCallback((taskId: string, inPlan: boolean): ContextMenuAction[] => {
    const task = tasks.find(t => t.id === taskId);
    if (!task) return [];
    const actions: ContextMenuAction[] = [];
    if (task.status === 'done') {
      actions.push({ label: 'Mark to-do', onClick: () => { handleMarkTodo(taskId); } });
    } else {
      actions.push({ label: 'Mark done', onClick: () => { handleMarkDone(taskId); } });
    }
    actions.push({ label: 'Edit', onClick: () => { setIsCreating(false); setSelectedId(taskId); } });
    if (sharedBoard) {
      if (task.assigneeUserId === currentUserId) {
        actions.push({ label: 'Unclaim', onClick: () => { void handleSetAssignee(taskId, null); } });
      } else {
        actions.push({ label: 'Claim', onClick: () => { void handleSetAssignee(taskId, currentUserId); } });
      }
    }
    if (!inPlan && currentPlan !== null) {
      actions.push({ label: "Add to this week's plan", onClick: () => {
        setScheduleModal({ taskId, title: task.title });
      }});
    }
    if (inPlan) {
      actions.push({ label: "Remove from this week's plan", onClick: () => { void handleRemoveFromPlan(taskId); } });
    }
    actions.push({ label: 'Delete', danger: true, onClick: () => { requestDelete(taskId); } });
    return actions;
  }, [tasks, currentPlan, sharedBoard, currentUserId, handleSetAssignee, handleMarkDone, handleMarkTodo, handleRemoveFromPlan, requestDelete]);

  const handleDragStart = (event: DragStartEvent) => {
    setDraggingId(String(event.active.id));
  };

  const handleDragEnd = useCallback(async (event: DragEndEvent) => {
    setDraggingId(null);
    const { active, over } = event;
    if (!over || active.id === over.id) return;

    const draggedId = String(active.id);
    const overId    = String(over.id);

    const oldIndex = visibleIds.indexOf(draggedId);
    const newIndex = visibleIds.indexOf(overId);
    if (oldIndex === -1 || newIndex === -1) return;

    const reordered = arrayMove(visibleTasks, oldIndex, newIndex);

    // Optimistic update with synthetic, lexicographically-comparable sort keys
    // so the local order is stable until the server confirms.
    setTasks(prev => {
      const reorderedById = new Map(reordered.map((t, i) => [t.id, i]));
      const others = prev.filter(t => !reorderedById.has(t.id));
      const updatedVisible = reordered.map((t, i) => ({
        ...t,
        sortKey: `~${String(i).padStart(6, '0')}`,
      }));
      return [...others, ...updatedVisible];
    });

    const afterId  = newIndex > 0                     ? reordered[newIndex - 1].id : null;
    const beforeId = newIndex < reordered.length - 1  ? reordered[newIndex + 1].id : null;

    if (!activeBoardId) return;
    try {
      await reorderTask(activeBoardId, draggedId, afterId, beforeId);
      const fresh = await fetchTasks(activeBoardId, fetchStatus);
      setTasks(fresh);
    } catch (e) {
      console.error('Failed to reorder task', e);
      fetchTasks(activeBoardId, fetchStatus).then(setTasks).catch(() => {});
    }
  }, [visibleIds, visibleTasks, fetchStatus, activeBoardId]);

  const handleDragCancel = () => setDraggingId(null);

  const activeBoard = boards.find(b => b.id === activeBoardId) ?? null;

  const switchBoard = useCallback((boardId: string) => {
    if (boardId === activeBoardId) return;
    localStorage.setItem(ACTIVE_BOARD_KEY, boardId);
    setSelectedId(null);
    setIsCreating(false);
    setFilter('todo');
    // The archived view is board-specific; drop it so it lazy-loads for the new board on demand.
    setShowArchived(false);
    setArchivedTasks([]);
    setActiveBoardId(boardId);
  }, [activeBoardId]);

  const handleCreateBoard = useCallback(async (name: string) => {
    const created = await createBoard(name);
    setBoards(prev => [...prev, created]);
    switchBoard(created.id);
  }, [switchBoard]);

  const handleRenameBoard = useCallback(async (name: string) => {
    if (!activeBoardId) return;
    const updated = await renameBoard(activeBoardId, name);
    setBoards(prev => prev.map(b => b.id === updated.id ? updated : b));
  }, [activeBoardId]);

  const handleDeleteBoard = useCallback(async () => {
    if (!activeBoardId) return;
    const remaining = boards.filter(b => b.id !== activeBoardId);
    if (remaining.length === 0) return; // backend enforces the same last-board guard
    await deleteBoard(activeBoardId);
    setBoards(remaining);
    switchBoard(remaining[0].id);
  }, [activeBoardId, boards, switchBoard]);

  // Re-sync the board list (e.g. after a membership change updates a board's member count).
  const refreshBoards = useCallback(() => {
    fetchBoards().then(setBoards).catch(e => console.error('Failed to refresh boards', e));
  }, []);

  // The user left the active (shared) board: drop it locally and switch to another of theirs.
  const handleLeftBoard = useCallback(async () => {
    setMembersOpen(false);
    const fresh = await fetchBoards().catch(() => null);
    if (!fresh || fresh.length === 0) { onSignOut(); return; }
    setBoards(fresh);
    switchBoard(fresh[0].id);
  }, [onSignOut, switchBoard]);

  const defaultCategoryId = settings.categories[0]?.id ?? null;

  const handleToggleArchived = () => {
    if (!showArchived && archivedTasks.length === 0 && activeBoardId) {
      fetchTasks(activeBoardId, 'archived').then(setArchivedTasks).catch(e => console.error('Failed to fetch archived tasks', e));
    }
    setShowArchived(v => !v);
  };

  const openNew = () => {
    if (settings.categories.length === 0) {
      setSettingsOpen(true);
      return;
    }
    setSelectedId(null);
    setIsCreating(true);
  };

  if (loading) {
    return <div className="board-wrap"><div className="board board--empty">Loading…</div></div>;
  }

  if (error || !activeBoardId) {
    return <div className="board-wrap"><div className="board board--empty">{error ?? 'Failed to load data. Is the backend running?'}</div></div>;
  }

  return (
    <>
      <header className="header">
        <BrandBoard
          boards={boards}
          activeBoardId={activeBoardId}
          onSwitch={switchBoard}
          onCreate={() => setBoardDialog({ mode: 'create' })}
          onRename={() => setBoardDialog({ mode: 'rename' })}
          onDelete={() => setBoardDeleteConfirm(true)}
          onManageMembers={() => setMembersOpen(true)}
        />
        <BoardFilter
          value={filter}
          onChange={(f) => {
            if (f !== 'all') { setShowArchived(false); setArchivedTasks([]); }
            setFilter(f);
          }}
          hasCurrentPlan={currentPlan !== null}
        />
        <div className="header-right">
          <button
            type="button"
            className="new-note-btn"
            onClick={openNew}
            aria-label="Add new task"
            title="Pin up a new task"
          >
            <span className="new-note-btn__plus">+</span>
          </button>
          <button
            type="button"
            className="icon-btn"
            onClick={() => {
              fetchCurrentPlan().then(setCurrentPlan).catch(e => console.error('Failed to refetch plan', e));
              setPlanDrawerOpen(true);
            }}
            aria-label="Open weekly plan"
            title="Weekly plan"
          >
            <PlanIcon />
          </button>
          <UserMenu
            displayName={settings.displayName}
            onOpenStats={() => setStatsOpen(true)}
            onOpenSettings={() => setSettingsOpen(true)}
            onSignOut={() => { void onSignOut(); }}
          />
        </div>
      </header>

      {!claimed && !nudgeDismissed && (
        <div className="account-nudge" role="status">
          <span className="account-nudge__text">Add an email or Telegram to keep your tasks safe.</span>
          <button
            type="button"
            className="account-nudge__cta"
            onClick={() => setSettingsOpen(true)}
          >
            Save my account
          </button>
          <button
            type="button"
            className="account-nudge__dismiss"
            aria-label="Dismiss"
            onClick={() => { localStorage.setItem('saveAccountNudgeDismissed', '1'); setNudgeDismissed(true); }}
          >
            ×
          </button>
        </div>
      )}

      {hasTutorialTasks && (
        <div className="tutorial-strip">
          <span className="tutorial-strip__text">These tutorial tasks are here to help you get started.</span>
          <button type="button" className="tutorial-strip__clear" onClick={() => { void handleClearTutorial(); }}>
            Clear tutorial
          </button>
        </div>
      )}

      <main className="board-wrap">
        {visibleTasks.length === 0 ? (
          <div className="board board--empty">{emptyMessageFor(filter)}</div>
        ) : (
          <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragStart={handleDragStart}
            onDragEnd={handleDragEnd}
            onDragCancel={handleDragCancel}
          >
            <SortableContext items={visibleIds} strategy={rectSortingStrategy}>
              <div
                className="board board--list"
                role="list"
                aria-label="Task list"
                data-dragging={draggingId ? 'true' : undefined}
              >
                {visibleTasks.map((task) => (
                  <PostItNote
                    key={task.id}
                    task={task}
                    category={categoryById.get(task.categoryId)}
                    leaving={leavingId === task.id}
                    inCurrentPlan={planId !== null && task.lastScheduledInSessionId === planId}
                    assignee={resolveAssignee(task)}
                    onClick={() => { setIsCreating(false); setSelectedId(task.id); }}
                    onContextMenu={e => setContextMenu({ x: e.clientX, y: e.clientY, taskId: task.id, inPlan: planId !== null && task.lastScheduledInSessionId === planId })}
                  />
                ))}
              </div>
            </SortableContext>
          </DndContext>
        )}
      </main>

      {filter === 'all' && (
        <div className="show-archived-wrap">
          <button type="button" className="show-archived-btn" onClick={handleToggleArchived}>
            {showArchived ? 'Hide archived' : 'Show archived'}
          </button>
        </div>
      )}

      <img className="pineapple-pet" src={pineappleUrl} alt="" aria-hidden="true" />

      <TaskDrawer
        task={selectedTask}
        isNew={isCreating}
        open={drawerOpen}
        categories={settings.categories}
        availableTags={tags}
        defaultCategoryId={defaultCategoryId}
        members={members}
        currentUserId={currentUserId}
        onClose={closeDrawer}
        onSave={handleSave}
        onDelete={requestDelete}
        onMarkDone={handleMarkDone}
        onMarkTodo={handleMarkTodo}
        onSetAssignee={handleSetAssignee}
      />

      <WeeklyPlanDrawer
        open={planDrawerOpen}
        onClose={() => setPlanDrawerOpen(false)}
        currentPlan={currentPlan}
        onTaskClick={(taskId) => {
          setPlanDrawerOpen(false);
          setIsCreating(false);
          setSelectedId(taskId);
        }}
        onTaskContextMenu={(e, taskId) => setContextMenu({ x: e.clientX, y: e.clientY, taskId, inPlan: true })}
        onFinalized={() => {
          if (!activeBoardId) return;
          // The assistant can create or edit tasks while planning, so refresh the board (and tags)
          // immediately rather than waiting for the next background poll.
          fetchCurrentPlan().then(setCurrentPlan).catch(e => console.error('Failed to refetch plan', e));
          Promise.all([fetchTasks(activeBoardId, fetchStatus), fetchTags(activeBoardId)])
            .then(([freshTasks, freshTags]) => { setTasks(freshTasks); setTags(freshTags); })
            .catch(e => console.error('Failed to refetch tasks after planning', e));
        }}
      />

      <SettingsModal
        boardId={activeBoardId}
        settings={settings}
        tasks={tasks}
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        onSave={setSettings}
        onAccountDeleted={() => { void onSignOut(); }}
      />

      <StatsModal open={statsOpen} onClose={() => setStatsOpen(false)} />

      <BoardMembersModal
        open={membersOpen}
        board={activeBoard}
        currentUserId={currentUserId}
        onClose={() => setMembersOpen(false)}
        onMembershipChanged={refreshBoards}
        onLeft={handleLeftBoard}
      />

      {contextMenu && (
        <ContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          actions={buildContextMenuActions(contextMenu.taskId, contextMenu.inPlan)}
          onClose={() => setContextMenu(null)}
        />
      )}

      <ConfirmDialog
        open={deleteConfirm !== null}
        title="Delete task"
        message={`Are you sure you want to delete "${deleteConfirm?.title}"? This cannot be undone.`}
        confirmLabel="Delete"
        danger
        onConfirm={() => { if (deleteConfirm) { void handleDelete(deleteConfirm.taskId); closeDrawer(); } }}
        onClose={() => setDeleteConfirm(null)}
      />

      {scheduleModal && currentPlan && (
        <ScheduleTaskModal
          key={scheduleModal.taskId}
          open={scheduleModal !== null}
          taskTitle={scheduleModal.title}
          weekStart={currentPlan.weekStart}
          weekEnd={currentPlan.weekEnd}
          onConfirm={(startIso, endIso) => {
            void handleAddToPlan(scheduleModal.taskId, startIso, endIso);
            setScheduleModal(null);
          }}
          onClose={() => setScheduleModal(null)}
        />
      )}

      <BoardNameDialog
        open={boardDialog !== null}
        title={boardDialog?.mode === 'rename' ? 'Rename board' : 'New board'}
        confirmLabel={boardDialog?.mode === 'rename' ? 'Save' : 'Create'}
        initialValue={boardDialog?.mode === 'rename' ? (activeBoard?.name ?? '') : ''}
        onConfirm={boardDialog?.mode === 'rename' ? handleRenameBoard : handleCreateBoard}
        onClose={() => setBoardDialog(null)}
      />

      <ConfirmDialog
        open={boardDeleteConfirm}
        title="Delete board"
        message={`Delete "${activeBoard?.name ?? 'this board'}" and all of its tasks, categories, and tags? This cannot be undone.`}
        confirmLabel="Delete board"
        danger
        onConfirm={() => { void handleDeleteBoard(); }}
        onClose={() => setBoardDeleteConfirm(false)}
      />
    </>
  );
}
