/**
 * The signed-in board surface: everything behind authentication (task board, drawers, modals,
 * drag-and-drop). Split out of `App.tsx` and loaded lazily so the anonymous landing page — the
 * first thing a new visitor and every crawler/benchmark downloads — doesn't pay for the editor,
 * drag-and-drop, and modal code it never renders. See `AuthShell` in `App.tsx` for the boundary.
 */
import { useState, useMemo, useCallback, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
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
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { PostItNote, type AssigneeChipInfo } from './components/PostItNote';
import { TaskLine } from './components/TaskLine';
import { ViewToggle } from './components/ViewToggle';
import { TaskDrawer } from './components/TaskDrawer';
import { SettingsModal } from './components/SettingsModal';
import { BoardFilter } from './components/BoardFilter';
import { SortMenu } from './components/SortMenu';
import { BrandBoard } from './components/BrandBoard';
import { BoardNameDialog } from './components/BoardNameDialog';
import { DuplicateBoardDialog } from './components/DuplicateBoardDialog';
import { WeeklyPlanDrawer } from './components/WeeklyPlanDrawer';
import { ContextMenu, type ContextMenuAction } from './components/ContextMenu';
import { ConfirmDialog } from './components/ConfirmDialog';
import { ScheduleTaskModal } from './components/ScheduleTaskModal';
import { MoveTaskModal } from './components/MoveTaskModal';
import { UserMenu } from './components/UserMenu';
import { StatsModal } from './components/StatsModal';
import { FeedbackModal } from './components/FeedbackModal';
import { BoardSettingsModal } from './components/BoardSettingsModal';
import { DEFAULT_SETTINGS } from './data';
import { fetchBoards, createBoard, duplicateBoard, fetchTasks, fetchCategories, fetchUserSettings, fetchTags, updateTag, fetchCurrentPlan, fetchSync, createTask, updateTask, deleteTask, duplicateTask, moveTaskToBoard, reorderTask, removeTaskFromPlan, clearTutorialTasks, addTaskToPlan, changeTaskSlot, notifyError, fetchMembers, setTaskAssignee, type TaskStatusFilter, type Board, type BoardMember } from './api';
import { notifyToast } from './toast';
import { formatDate } from './i18n/format';
import type { Task, UserSettings, Tag, CurrentPlan, TaskFilter, ViewMode } from './types';
import { sortTasks, isSortMode, type SortMode } from './sort';
import { toggleChecklistItem } from './checklist';
import { isFutureDate, todayIso } from './recurrence';

const ACTIVE_BOARD_KEY = 'backlog.activeBoardId';
// Sort mode is a per-board view preference (per device); the manual order itself lives server-side.
const SORT_MODE_KEY = 'backlog.sortMode:';
// Board vs. compact view is likewise a per-board, per-device preference.
const VIEW_MODE_KEY = 'backlog.viewMode:';
import { useLocation, useNavigate } from 'react-router-dom';
import { resolveTaskLink, settingsTabFromPath, type BoardSettingsTab } from './taskLink';
import { useAuth } from './auth/AuthContext';
import { useTimeZoneDetection } from './auth/useTimeZoneDetection';
import { mascotFor } from './mascots';
import BalloonCelebration from './components/BalloonCelebration';
import i18n from './i18n';
import { useBranding } from './publicConfig';


/** Up to two initials from a display name (e.g. "Dana Scully" → "DS", "it***@gmail.com" → "IT"). */
function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

function emptyMessageFor(filter: TaskFilter): string {
  switch (filter) {
    case 'todo': return i18n.t('app.emptyTodo');
    case 'plan': return i18n.t('app.emptyPlan');
    case 'recurring': return i18n.t('app.emptyRecurring');
    case 'all':  return i18n.t('app.emptyAll');
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

export default function Board({ onSignOut }: { onSignOut: () => Promise<void> }) {
  const { t } = useTranslation();
  const { name: appName } = useBranding();
  const { state: authState } = useAuth();
  useTimeZoneDetection();
  const location = useLocation();
  const navigate = useNavigate();
  // Settings is a URL-driven modal: `/settings[/<tab>]` opens it on the matching tab, so deep links
  // (e.g. from a tutorial task's link) are shareable and refresh-safe.
  const settingsOpen = location.pathname.startsWith('/settings');
  const settingsTab = settingsTabFromPath(location.pathname);
  const closeSettings = useCallback(() => navigate('/'), [navigate]);
  const currentUserId = authState.status === 'authenticated' ? authState.user.id : null;
  // Unclaimed = zero-registration account with no login identity yet; nudge them to save it.
  const claimed = authState.status === 'authenticated' ? authState.user.claimed : true;
  const [nudgeDismissed, setNudgeDismissed] = useState(() => localStorage.getItem('saveAccountNudgeDismissed') === '1');
  const nudgeVisible = !claimed && !nudgeDismissed;

  // The nudge is a fixed band pinned to the bottom of the viewport, so it would otherwise sit on
  // top of the footer links and the archived toggle. Reserve its measured height on #root — it
  // wraps to two lines on narrow screens and differs per language, so guessing a constant would
  // be wrong somewhere. A callback ref (with React 19's cleanup return) rather than an effect:
  // the nudge only mounts once the board has loaded, well after this component first renders.
  const measureNudge = useCallback((nudge: HTMLDivElement | null) => {
    const root = document.getElementById('root');
    if (!nudge || !root) return;
    const observer = new ResizeObserver(() => root.style.setProperty('--nudge-h', `${nudge.offsetHeight}px`));
    observer.observe(nudge);
    return () => {
      observer.disconnect();
      root.style.removeProperty('--nudge-h');
    };
  }, []);
  // One active board at a time; every account has at least one (the backend lists them default-first).
  const [boards, setBoards]           = useState<Board[]>([]);
  const [activeBoardId, setActiveBoardId] = useState<string | null>(null);
  // The create dialog is a small name prompt; rename/mascot/members/delete all live in the settings modal.
  const [boardCreateOpen, setBoardCreateOpen] = useState(false);
  const [boardSettingsOpen, setBoardSettingsOpen] = useState(false);
  const [boardSettingsTab, setBoardSettingsTab] = useState<BoardSettingsTab>('general');
  const [boardDuplicateOpen, setBoardDuplicateOpen] = useState(false);
  const [tasks, setTasks]             = useState<Task[]>([]);
  const [tags, setTags]               = useState<Tag[]>([]);
  const [settings, setSettings]       = useState<UserSettings>({ ...DEFAULT_SETTINGS, categories: [] });
  const [selectedId, setSelectedId]   = useState<string | null>(null);
  const [isCreating, setIsCreating]   = useState(false);
  const [statsOpen, setStatsOpen] = useState(false);
  const [feedbackOpen, setFeedbackOpen] = useState(false);
  // Keyed to its board so a stale fetch from a previous board is ignored without a synchronous reset.
  const [memberData, setMemberData]   = useState<{ boardId: string; members: BoardMember[] } | null>(null);
  const [leavingId, setLeavingId]     = useState<string | null>(null);
  // One entry per completion in flight; each balloon retires itself when it leaves the screen.
  const [celebrations, setCelebrations] = useState<number[]>([]);
  const celebrationSeq = useRef(0);
  const [loading, setLoading]         = useState(true);
  // Always the same failure text (the initial bootstrap fetch), so a boolean is enough; the message
  // itself is resolved at render time via t() rather than stored, so it stays correct across a
  // language switch.
  const [error, setError]             = useState(false);
  const [draggingId, setDraggingId]   = useState<string | null>(null);
  const [filter, setFilter]           = useState<TaskFilter>('todo');
  const [sortModeByBoard, setSortModeByBoard] = useState<Record<string, SortMode>>({});
  const [viewModeByBoard, setViewModeByBoard] = useState<Record<string, ViewMode>>({});
  const [showArchived, setShowArchived] = useState(false);
  const [archivedTasks, setArchivedTasks] = useState<Task[]>([]);
  const [currentPlan, setCurrentPlan]  = useState<CurrentPlan | null>(null);
  const [planDrawerOpen, setPlanDrawerOpen] = useState(false);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; taskId: string; inPlan: boolean } | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<{ taskId: string; title: string } | null>(null);
  const [scheduleModal, setScheduleModal] = useState<{
    taskId: string;
    title: string;
    estimatedMinutes?: number;
    /** Present when rescheduling an already-planned task; drives prefill + the update path. */
    initialSlot?: { startIso: string; endIso: string };
  } | null>(null);
  const [moveModal, setMoveModal] = useState<{ taskId: string; title: string; categoryLabel: string | null } | null>(null);
  // Last-seen sync watermarks (per board) + the version baseline. The first poll after a board switch
  // just records these; later polls refetch only the entity types whose timestamp moved.
  const syncState = useRef<{
    boardId: string;
    tasks: string | null;
    tags: string | null;
    categories: string | null;
    plan: string | null;
    version: string;
  } | null>(null);
  const [updateAvailable, setUpdateAvailable] = useState(false);

  // The redeploy nudge rides the shared toast stack rather than its own fixed banner, so it can't
  // overlap an error toast or the account-claim band. Persistent: a tab on stale JS should keep the
  // offer in view until it is taken or dismissed.
  useEffect(() => {
    if (!updateAvailable) return;
    notifyToast({
      key: 'app-update',
      message: t('updateBanner.message', { appName }),
      durationMs: 0,
      action: { label: t('updateBanner.refresh'), onClick: () => window.location.reload() },
    });
  }, [updateAvailable, t, appName]);

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
        setError(true);
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
  // and applies plan-membership client-side; 'recurring' needs the full list, since the open-tasks
  // fetch hides recurring tasks waiting for their next date. This also clears the initial loading flag.
  const fetchStatus: TaskStatusFilter = filter === 'plan' ? 'todo' : filter === 'recurring' ? 'all' : filter;
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

  // Periodic sync — one lightweight call every 60 s returns a watermark per entity type; refetch only
  // what moved. Skips while the tab is hidden or a drawer is open; on visibility restore, catches up.
  useEffect(() => {
    if (loading || !activeBoardId) return;
    const boardId = activeBoardId;

    const sync = async () => {
      if (drawerOpen || document.hidden) return;
      try {
        const s = await fetchSync(boardId);
        const prev = syncState.current;

        // First poll for this board: record the baseline (initial load already fetched everything).
        if (!prev || prev.boardId !== boardId) {
          syncState.current = {
            boardId,
            tasks: s.tasksChangedAt, tags: s.tagsChangedAt,
            categories: s.categoriesChangedAt, plan: s.planChangedAt,
            version: s.appVersion,
          };
          return;
        }

        if (s.appVersion !== prev.version) setUpdateAvailable(true);

        const jobs: Promise<void>[] = [];
        if (s.tasksChangedAt !== prev.tasks) {
          jobs.push(fetchTasks(boardId, fetchStatus).then(setTasks));
        }
        if (s.tagsChangedAt !== prev.tags) {
          jobs.push(fetchTags(boardId).then(setTags));
        }
        if (s.categoriesChangedAt !== prev.categories) {
          jobs.push(fetchCategories(boardId).then(c => setSettings(p => ({ ...p, categories: c }))));
        }
        if (s.planChangedAt !== prev.plan) {
          jobs.push(fetchCurrentPlan().then(setCurrentPlan));
        }
        await Promise.all(jobs);

        // Keep the original version baseline so the banner stays up once a redeploy is detected.
        syncState.current = {
          boardId,
          tasks: s.tasksChangedAt, tags: s.tagsChangedAt,
          categories: s.categoriesChangedAt, plan: s.planChangedAt,
          version: prev.version,
        };
      } catch { /* silent — don't surface background network blips */ }
    };

    // Baseline immediately on entering a board (not a full interval later) so a change landing between
    // the initial load and the first poll isn't silently adopted as the baseline.
    if (!syncState.current || syncState.current.boardId !== boardId) void sync();

    const id = setInterval(sync, 60_000);
    document.addEventListener('visibilitychange', sync);
    return () => {
      clearInterval(id);
      document.removeEventListener('visibilitychange', sync);
    };
  }, [loading, drawerOpen, fetchStatus, activeBoardId]);
  const selectedTask = tasks.find(t => t.id === selectedId) ?? null;

  // Sort mode is a per-board view preference. It's kept in an in-memory map (so a change re-renders)
  // that lazily seeds from localStorage the first time a board is viewed — no set-state-in-effect.
  const sortMode: SortMode = useMemo(() => {
    if (!activeBoardId) return 'none';
    if (activeBoardId in sortModeByBoard) return sortModeByBoard[activeBoardId];
    const stored = localStorage.getItem(SORT_MODE_KEY + activeBoardId);
    return isSortMode(stored) ? stored : 'none';
  }, [activeBoardId, sortModeByBoard]);

  const changeSortMode = useCallback((mode: SortMode) => {
    if (!activeBoardId) return;
    localStorage.setItem(SORT_MODE_KEY + activeBoardId, mode);
    setSortModeByBoard(prev => ({ ...prev, [activeBoardId]: mode }));
  }, [activeBoardId]);

  // View mode mirrors the sort-mode pattern: an in-memory map that lazily seeds from localStorage.
  const viewMode: ViewMode = useMemo(() => {
    if (!activeBoardId) return 'board';
    if (activeBoardId in viewModeByBoard) return viewModeByBoard[activeBoardId];
    return localStorage.getItem(VIEW_MODE_KEY + activeBoardId) === 'compact' ? 'compact' : 'board';
  }, [activeBoardId, viewModeByBoard]);

  const changeViewMode = useCallback((mode: ViewMode) => {
    if (!activeBoardId) return;
    localStorage.setItem(VIEW_MODE_KEY + activeBoardId, mode);
    setViewModeByBoard(prev => ({ ...prev, [activeBoardId]: mode }));
  }, [activeBoardId]);

  const sortedTasks = useMemo(() => sortTasks(tasks, sortMode), [tasks, sortMode]);

  const planId = currentPlan?.id ?? null;
  const sortedArchivedTasks = useMemo(() => sortTasks(archivedTasks, sortMode), [archivedTasks, sortMode]);

  // Hand-reorder only makes sense against the manual order; a field sort suspends drag.
  // The Recurring view is ordered by next date, so hand-reorder has nothing to act on there.
  const canReorder = sortMode === 'none' && filter !== 'recurring';
  const visibleTasks = useMemo(() => {
    const today = todayIso();
    const base = sortedTasks.filter(t => {
      if (t.id === leavingId) return true;
      switch (filter) {
        // The server's todo list already omits future-dated tasks; checking here as well drops a
        // recurring task the moment it rolls forward, without waiting for a refetch.
        case 'todo': return t.status === 'todo' && !isFutureDate(t.relevantFrom, today);
        case 'recurring': return t.recurrence != null && t.status !== 'archived';
        case 'all':  return t.status !== 'archived';
        case 'plan': return t.status === 'todo' && !isFutureDate(t.relevantFrom, today) && planId !== null && t.lastScheduledInSessionId === planId;
      }
    });
    if (filter === 'recurring' && sortMode === 'none') {
      base.sort((a, b) => (a.relevantFrom ?? '').localeCompare(b.relevantFrom ?? ''));
    }
    return filter === 'all' && showArchived ? [...base, ...sortedArchivedTasks] : base;
  }, [sortedTasks, sortedArchivedTasks, leavingId, filter, planId, showArchived, sortMode]);

  // A rolled-forward recurring task keeps its plan stamp (the plan view joins through it), but it's
  // done for this week — so don't badge it as planned or offer plan actions on it.
  const isInCurrentPlan = useCallback((task: Task) =>
    planId !== null && task.lastScheduledInSessionId === planId && !(task.recurrence && isFutureDate(task.relevantFrom)),
  [planId]);

  const visibleIds = useMemo(() => visibleTasks.map(t => t.id), [visibleTasks]);

  const categoryById = useMemo(
    () => new Map(settings.categories.map(c => [c.id, c])),
    [settings.categories],
  );

  const closeDrawer = () => { setSelectedId(null); setIsCreating(false); };

  const handleSave = async (updated: Omit<Task, 'sortKey'>) => {
    if (!activeBoardId) return;
    try {
      const { id: _id, createdAt: _ca, ...payload } = updated;
      // The drawer surfaces save failures itself (inline field errors + a form-level banner),
      // so opt out of the global toast to avoid double-reporting.
      if (isCreating) {
        const created = await createTask(activeBoardId, payload, { emitErrors: false });
        setTasks(prev => [...prev, created]);
      } else {
        const saved = await updateTask(activeBoardId, updated.id, payload, { emitErrors: false });
        setTasks(prev => prev.map(t => t.id === saved.id ? saved : t));
      }

      // Refetch tags in case a new tag was created
      fetchTags(activeBoardId).then(setTags).catch(e => console.error('Failed to refetch tags', e));

      closeDrawer();
    } catch (e) {
      console.error('Failed to save task', e);
      // Re-throw so the drawer can surface field-level validation errors inline (and stay open).
      throw e;
    }
  };

  const handleDelete = useCallback(async (id: string) => {
    if (!activeBoardId) return;
    try {
      await deleteTask(activeBoardId, id);
      setTasks(prev => prev.filter(t => t.id !== id));
      // No undo: the delete is already behind a confirmation, and the row is gone server-side.
      notifyToast({ key: 'task-deleted', message: t('toast.taskDeleted') });
    } catch (e) {
      console.error('Failed to delete task', e);
    }
  }, [activeBoardId, t]);

  const handleDuplicate = useCallback(async (id: string) => {
    if (!activeBoardId) return;
    try {
      const copy = await duplicateTask(activeBoardId, id);
      setTasks(prev => [...prev, copy]);
    } catch (e) {
      console.error('Failed to duplicate task', e);
    }
  }, [activeBoardId]);

  const handleMoveToBoard = useCallback(async (id: string, targetBoardId: string, categoryId: string) => {
    if (!activeBoardId) return;
    try {
      await moveTaskToBoard(activeBoardId, id, targetBoardId, categoryId);
      // The task now lives on another board — drop it from the current board's view and any plan.
      setTasks(prev => prev.filter(t => t.id !== id));
      setCurrentPlan(prev => prev ? { ...prev, tasks: prev.tasks.filter(t => t.id !== id) } : prev);
    } catch (e) {
      console.error('Failed to move task', e);
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

  /** Refetch the current plan, then reveal the planning drawer. Shared by the header button and
   *  the `app:open-planner` tutorial card so the two can't drift. */
  const openPlanDrawer = useCallback(() => {
    fetchCurrentPlan().then(setCurrentPlan).catch(e => console.error('Failed to refetch plan', e));
    setPlanDrawerOpen(true);
  }, []);

  const followTaskLink = useCallback((task: Task) => {
    const link = resolveTaskLink(task.url);
    if (!link) return;
    // Following a link supersedes the task view: dismiss the drawer so it doesn't linger behind the
    // settings modal (internal links) or point at a since-cleared task (the clear-tutorial action).
    setSelectedId(null);
    setIsCreating(false);
    switch (link.kind) {
      case 'external': window.open(link.href, '_blank', 'noopener,noreferrer'); break;
      case 'internal': navigate(link.to); break;
      case 'action':
        switch (link.action) {
          case 'clear-tutorial': void handleClearTutorial(); break;
          case 'open-planner': openPlanDrawer(); break;
        }
        break;
    }
  }, [navigate, handleClearTutorial, openPlanDrawer]);

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

  /**
   * The post-completion toast. Undo is offered only for a plain task, where it is exactly the
   * "mark to-do" flip: completing a *recurring* task also files a DONE copy of the occurrence and
   * rolls the schedule forward server-side (docs/RECURRING-TASKS.md), so a status flip would not
   * undo it — those get the next occurrence's date instead. Keyed, so a burst of completions
   * leaves one undo offer rather than a column of them.
   */
  const announceCompletion = useCallback((saved: Task) => {
    const nextDate = saved.recurrence && saved.relevantFrom
      ? formatDate(new Date(`${saved.relevantFrom}T00:00:00`), { month: 'short', day: 'numeric' })
      : null;
    notifyToast({
      key: 'task-done',
      kind: 'success',
      message: nextDate ? t('toast.recurringDone', { date: nextDate }) : t('toast.taskDone'),
      action: saved.recurrence
        ? undefined
        : { label: t('toast.undo'), onClick: () => handleMarkTodo(saved.id) },
    });
  }, [handleMarkTodo, t]);

  const handleMarkDone = useCallback((id: string) => {
    const task = tasks.find(t => t.id === id);
    if (!task || !activeBoardId) return;
    // A recurring task only moves to its next date, so it stays put on the Recurring and All views.
    const leaves = !task.recurrence || filter === 'todo' || filter === 'plan';
    if (leaves) setLeavingId(id);
    // Fires for recurring tasks too — the row stays put, but the occurrence was still completed.
    // Capped so a burst of completions doesn't fill the screen with balloons.
    setCelebrations(prev => [...prev, ++celebrationSeq.current].slice(-3));
    const { id: _id, createdAt: _ca, sortKey: _sk, ...payload } = task;
    updateTask(activeBoardId, id, { ...payload, status: 'done' }).then(saved => {
      announceCompletion(saved);
      setTimeout(() => {
        // Trust the response: a recurring task comes back `todo` with its next relevantFrom/deadline.
        setTasks(prev => prev.map(t => t.id === id ? saved : t));
        setLeavingId(null);
        if (saved.recurrence) {
          // The server also saved a DONE copy of this occurrence, and the plan now shows it as done.
          fetchTasks(activeBoardId, fetchStatus).then(setTasks).catch(e => console.error('Failed to refresh tasks', e));
          fetchCurrentPlan().then(setCurrentPlan).catch(e => console.error('Failed to refetch plan', e));
        }
      }, leaves ? 380 : 0);
    }).catch(e => {
      console.error('Failed to mark task done', e);
      setLeavingId(null);
    });
  }, [tasks, activeBoardId, filter, fetchStatus, announceCompletion]);

  /**
   * Flips one checklist item in a task's note straight from the board (#237). Optimistic, since a
   * checkbox that lags the click feels broken; a failed save puts the old note back.
   */
  const handleToggleChecklistItem = useCallback((id: string, index: number) => {
    const task = tasks.find(t => t.id === id);
    if (!task?.description || !activeBoardId) return;
    const description = toggleChecklistItem(task.description, index);
    if (description === task.description) return;
    const setDescription = (value: string) =>
      setTasks(prev => prev.map(t => t.id === id ? { ...t, description: value } : t));
    setDescription(description);
    const { id: _id, createdAt: _ca, sortKey: _sk, ...payload } = task;
    updateTask(activeBoardId, id, { ...payload, description }).catch(e => {
      console.error('Failed to toggle checklist item', e);
      setDescription(task.description!);
    });
  }, [tasks, activeBoardId]);

  /** Brings a waiting recurring task back now (e.g. after an accidental "done"). The server re-derives the deadline. */
  const handleDoItNow = useCallback(async (id: string) => {
    const task = tasks.find(t => t.id === id);
    if (!task || !activeBoardId) return;
    const { id: _id, createdAt: _ca, sortKey: _sk, ...payload } = task;
    try {
      const saved = await updateTask(activeBoardId, id, { ...payload, relevantFrom: todayIso() });
      setTasks(prev => prev.map(t => t.id === id ? saved : t));
    } catch (e) {
      console.error('Failed to bring recurring task forward', e);
    }
  }, [tasks, activeBoardId]);

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

  // Pull the plan + tasks back into sync after a plan mutation. A failure here means the change
  // already landed but the view is stale; the fetch's own toast covers it, so just log.
  const refreshPlanAndTasks = useCallback(async () => {
    if (!activeBoardId) return;
    try {
      const [freshPlan, freshTasks] = await Promise.all([fetchCurrentPlan(), fetchTasks(activeBoardId, fetchStatus)]);
      setCurrentPlan(freshPlan);
      setTasks(freshTasks);
    } catch (e) {
      console.error('Failed to refresh plan after a change', e);
    }
  }, [activeBoardId, fetchStatus]);

  const handleAddToPlan = useCallback(async (taskId: string, startIso: string, endIso: string) => {
    if (!currentPlan || !activeBoardId) return;
    try {
      await addTaskToPlan(taskId, startIso, endIso, { emitErrors: false });
    } catch (e) {
      console.error('Failed to add task to plan', e);
      notifyError(t('app.addToPlanFailed'));
      return;
    }
    await refreshPlanAndTasks();
  }, [currentPlan, activeBoardId, refreshPlanAndTasks, t]);

  const handleChangeSlot = useCallback(async (taskId: string, startIso: string, endIso: string) => {
    if (!currentPlan || !activeBoardId) return;
    try {
      await changeTaskSlot(taskId, startIso, endIso, { emitErrors: false });
    } catch (e) {
      console.error('Failed to change task slot', e);
      notifyError(t('app.changeSlotFailed'));
      return;
    }
    await refreshPlanAndTasks();
  }, [currentPlan, activeBoardId, refreshPlanAndTasks, t]);

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
    const name = member?.displayName ?? t('app.member');
    return { initials: initialsOf(name), name, isMe: task.assigneeUserId === currentUserId };
  }, [sharedBoard, membersById, currentUserId, t]);

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
      actions.push({ label: t('app.contextMenu.markTodo'), onClick: () => { handleMarkTodo(taskId); } });
    } else {
      actions.push({ label: t('app.contextMenu.markDone'), onClick: () => { handleMarkDone(taskId); } });
    }
    if (task.recurrence && isFutureDate(task.relevantFrom)) {
      actions.push({ label: t('app.contextMenu.doItNow'), onClick: () => { void handleDoItNow(taskId); } });
    }
    actions.push({ label: t('app.contextMenu.edit'), onClick: () => { setIsCreating(false); setSelectedId(taskId); } });
    if (sharedBoard) {
      if (task.assigneeUserId === currentUserId) {
        actions.push({ label: t('app.contextMenu.unclaim'), onClick: () => { void handleSetAssignee(taskId, null); } });
      } else {
        actions.push({ label: t('app.contextMenu.claim'), onClick: () => { void handleSetAssignee(taskId, currentUserId); } });
      }
    }
    if (!inPlan && currentPlan !== null) {
      actions.push({ label: t('app.contextMenu.addToPlan'), onClick: () => {
        setScheduleModal({ taskId, title: task.title, estimatedMinutes: task.estimatedMinutes });
      }});
    }
    if (inPlan) {
      // Quick-switch a single planned slot without a full plan revision. Only offered for the
      // single-slot common case; multi-slot tasks (agent-created) stay on the revise flow.
      const plannedSlots = currentPlan?.tasks.find(pt => pt.id === taskId)?.slots ?? [];
      if (plannedSlots.length === 1) {
        actions.push({ label: t('app.contextMenu.changeSlot'), onClick: () => {
          setScheduleModal({ taskId, title: task.title, estimatedMinutes: task.estimatedMinutes, initialSlot: plannedSlots[0] });
        }});
      }
      actions.push({ label: t('app.contextMenu.removeFromPlan'), onClick: () => { void handleRemoveFromPlan(taskId); } });
    }
    actions.push({ label: t('app.contextMenu.duplicate'), onClick: () => { void handleDuplicate(taskId); } });
    if (boards.length > 1) {
      actions.push({ label: t('app.contextMenu.moveToBoard'), onClick: () => {
        setMoveModal({ taskId, title: task.title, categoryLabel: categoryById.get(task.categoryId)?.label ?? null });
      }});
    }
    actions.push({ label: t('app.contextMenu.delete'), danger: true, onClick: () => { requestDelete(taskId); } });
    return actions;
  }, [tasks, boards, categoryById, currentPlan, sharedBoard, currentUserId, handleSetAssignee, handleMarkDone, handleDoItNow, handleMarkTodo, handleRemoveFromPlan, handleDuplicate, requestDelete, t]);

  const handleDragStart = (event: DragStartEvent) => {
    setDraggingId(String(event.active.id));
  };

  const handleDragEnd = useCallback(async (event: DragEndEvent) => {
    setDraggingId(null);
    // Reorder is disabled under a field sort; the notes aren't draggable, but guard defensively.
    if (sortMode !== 'none') return;
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
  }, [visibleIds, visibleTasks, fetchStatus, activeBoardId, sortMode]);

  const handleDragCancel = () => setDraggingId(null);

  const activeBoard = boards.find(b => b.id === activeBoardId) ?? null;
  const activeMascot = mascotFor(activeBoard?.mascot);

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

  const handleDuplicateBoard = useCallback(async (name: string, resetTaskStatus: boolean) => {
    if (!activeBoardId) return;
    const created = await duplicateBoard(activeBoardId, name, resetTaskStatus);
    setBoards(prev => [...prev, created]);
    switchBoard(created.id);
  }, [activeBoardId, switchBoard]);

  // Name/mascot edits are persisted by the settings modal; here we only sync the local list.
  const handleBoardChanged = useCallback((updated: Board) => {
    setBoards(prev => prev.map(b => b.id === updated.id ? updated : b));
  }, []);

  // The settings modal performs the delete; we drop it locally and switch to another board.
  const handleBoardDeleted = useCallback(() => {
    setBoardSettingsOpen(false);
    if (!activeBoardId) return;
    const remaining = boards.filter(b => b.id !== activeBoardId);
    if (remaining.length === 0) return; // backend enforces the same last-board guard
    setBoards(remaining);
    switchBoard(remaining[0].id);
  }, [activeBoardId, boards, switchBoard]);

  // Re-sync the board list (e.g. after a membership change updates a board's member count).
  const refreshBoards = useCallback(() => {
    fetchBoards().then(setBoards).catch(e => console.error('Failed to refresh boards', e));
  }, []);

  // The user left the active (shared) board: drop it locally and switch to another of theirs.
  const handleLeftBoard = useCallback(async () => {
    setBoardSettingsOpen(false);
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
      setBoardSettingsTab('labels');
      setBoardSettingsOpen(true);
      return;
    }
    setSelectedId(null);
    setIsCreating(true);
  };

  if (loading) {
    return <div className="board-wrap"><div className="board board--empty">{t('app.loading')}</div></div>;
  }

  if (error || !activeBoardId) {
    return <div className="board-wrap"><div className="board board--empty">{t('app.loadError')}</div></div>;
  }

  return (
    <>
      <header className="header">
        <BrandBoard
          boards={boards}
          activeBoardId={activeBoardId}
          onSwitch={switchBoard}
          onCreate={() => setBoardCreateOpen(true)}
          onOpenSettings={() => { setBoardSettingsTab('general'); setBoardSettingsOpen(true); }}
          onDuplicate={() => setBoardDuplicateOpen(true)}
        />
        <SortMenu value={sortMode} onChange={changeSortMode} />
        <ViewToggle value={viewMode} onChange={changeViewMode} />
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
            aria-label={t('app.addTaskAria')}
            title={t('app.addTaskTitle')}
          >
            <span className="new-note-btn__plus">+</span>
          </button>
          <button
            type="button"
            className="icon-btn"
            onClick={openPlanDrawer}
            aria-label={t('app.openPlanAria')}
            title={t('app.openPlanTitle')}
          >
            <PlanIcon />
          </button>
          <UserMenu
            displayName={settings.displayName}
            onOpenStats={() => setStatsOpen(true)}
            onOpenSettings={() => navigate('/settings')}
            onOpenFeedback={() => setFeedbackOpen(true)}
            onSignOut={() => { void onSignOut(); }}
          />
        </div>
      </header>

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
            <SortableContext
              items={visibleIds}
              strategy={viewMode === 'compact' ? verticalListSortingStrategy : rectSortingStrategy}
            >
              <div
                className={viewMode === 'compact' ? 'board board--stack' : 'board board--list'}
                role="list"
                aria-label={t('app.taskListAria')}
                data-dragging={draggingId ? 'true' : undefined}
              >
                {visibleTasks.map((task) => {
                  const props = {
                    task,
                    category: categoryById.get(task.categoryId),
                    leaving: leavingId === task.id,
                    inCurrentPlan: isInCurrentPlan(task),
                    assignee: resolveAssignee(task),
                    draggable: canReorder,
                    onClick: () => { setIsCreating(false); setSelectedId(task.id); },
                    onContextMenu: (e: React.MouseEvent) => setContextMenu({ x: e.clientX, y: e.clientY, taskId: task.id, inPlan: isInCurrentPlan(task) }),
                    onFollowLink: () => followTaskLink(task),
                    // Tutorial tasks are read-only on the client.
                    onToggleCheckbox: task.tutorial ? undefined : (index: number) => handleToggleChecklistItem(task.id, index),
                  };
                  return viewMode === 'compact'
                    ? <TaskLine key={task.id} {...props} />
                    : <PostItNote key={task.id} {...props} />;
                })}
              </div>
            </SortableContext>
          </DndContext>
        )}
      </main>

      {filter === 'all' && (
        <div className="show-archived-wrap">
          <button type="button" className="show-archived-btn" onClick={handleToggleArchived}>
            {showArchived ? t('app.hideArchived') : t('app.showArchived')}
          </button>
        </div>
      )}

      <img
        className="pineapple-pet"
        src={activeMascot.url}
        srcSet={activeMascot.srcSet}
        sizes={activeMascot.sizes}
        alt=""
        aria-hidden="true"
        decoding="async"
        fetchPriority="low"
      />

      <TaskDrawer
        task={selectedTask}
        isNew={isCreating}
        open={drawerOpen}
        categories={settings.categories}
        availableTags={tags}
        defaultCategoryId={defaultCategoryId}
        members={members}
        currentUserId={currentUserId}
        aiEnabled={settings.aiEnabled}
        onClose={closeDrawer}
        onSave={handleSave}
        onDelete={requestDelete}
        onMarkDone={handleMarkDone}
        onMarkTodo={handleMarkTodo}
        onSetAssignee={handleSetAssignee}
        onUpdateTag={(tagId, label, colorId) => {
          if (!activeBoardId) return;
          // Board-wide tag rename/recolor from the inline editor; refresh tags + tasks (tapes embed label/color).
          updateTag(activeBoardId, tagId, { label, colorId })
            .then(() => Promise.all([fetchTags(activeBoardId), fetchTasks(activeBoardId, fetchStatus)]))
            .then(([freshTags, freshTasks]) => { setTags(freshTags); setTasks(freshTasks); })
            .catch(e => console.error('Failed to update tag', e));
        }}
        onFollowLink={followTaskLink}
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
        settings={settings}
        open={settingsOpen}
        initialTab={settingsTab}
        onClose={closeSettings}
        onSave={setSettings}
        onAccountDeleted={() => { void onSignOut(); }}
      />

      <StatsModal open={statsOpen} onClose={() => setStatsOpen(false)} />

      <FeedbackModal open={feedbackOpen} onClose={() => setFeedbackOpen(false)} />

      <BoardSettingsModal
        open={boardSettingsOpen}
        board={activeBoard}
        currentUserId={currentUserId}
        canDelete={boards.length > 1}
        canInvite={claimed}
        initialTab={boardSettingsTab}
        categories={settings.categories}
        tags={tags}
        tasks={tasks}
        onClose={() => setBoardSettingsOpen(false)}
        onMembershipChanged={refreshBoards}
        onBoardChanged={handleBoardChanged}
        onCategoriesChanged={next => setSettings(prev => ({ ...prev, categories: next }))}
        onTagsChanged={() => {
          // Tag rename/recolor/delete fans out to tasks (they embed the tag label/color), so refresh both.
          fetchTags(activeBoardId).then(setTags).catch(e => console.error('Failed to refetch tags', e));
          fetchTasks(activeBoardId, fetchStatus).then(setTasks).catch(e => console.error('Failed to refetch tasks', e));
        }}
        onLeft={handleLeftBoard}
        onDeleted={handleBoardDeleted}
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
        title={t('app.deleteTaskTitle')}
        message={t('app.deleteTaskMessage', { title: deleteConfirm?.title ?? '' })}
        confirmLabel={t('app.deleteConfirmLabel')}
        danger
        onConfirm={() => { if (deleteConfirm) { void handleDelete(deleteConfirm.taskId); closeDrawer(); } }}
        onClose={() => setDeleteConfirm(null)}
      />

      {scheduleModal && currentPlan && (
        <ScheduleTaskModal
          key={`${scheduleModal.taskId}-${scheduleModal.initialSlot ? 'edit' : 'add'}`}
          open={scheduleModal !== null}
          taskTitle={scheduleModal.title}
          weekStart={currentPlan.weekStart}
          weekEnd={currentPlan.weekEnd}
          estimatedMinutes={scheduleModal.estimatedMinutes}
          initialSlot={scheduleModal.initialSlot}
          onConfirm={(startIso, endIso) => {
            if (scheduleModal.initialSlot) {
              void handleChangeSlot(scheduleModal.taskId, startIso, endIso);
            } else {
              void handleAddToPlan(scheduleModal.taskId, startIso, endIso);
            }
            setScheduleModal(null);
          }}
          onClose={() => setScheduleModal(null)}
        />
      )}

      {moveModal && (
        <MoveTaskModal
          key={moveModal.taskId}
          open={moveModal !== null}
          taskTitle={moveModal.title}
          currentCategoryLabel={moveModal.categoryLabel}
          boards={boards.filter(b => b.id !== activeBoardId)}
          onConfirm={(targetBoardId, categoryId) => {
            void handleMoveToBoard(moveModal.taskId, targetBoardId, categoryId);
            setMoveModal(null);
          }}
          onClose={() => setMoveModal(null)}
        />
      )}

      <BoardNameDialog
        open={boardCreateOpen}
        title={t('app.newBoardTitle')}
        confirmLabel={t('app.createBoard')}
        onConfirm={handleCreateBoard}
        onClose={() => setBoardCreateOpen(false)}
      />

      <DuplicateBoardDialog
        open={boardDuplicateOpen}
        sourceBoardName={activeBoard?.name ?? ''}
        onConfirm={handleDuplicateBoard}
        onClose={() => setBoardDuplicateOpen(false)}
      />

      {nudgeVisible && (
        <div className="account-nudge" role="status" ref={measureNudge}>
          <span className="account-nudge__text">{t('app.nudgeText')}</span>
          <button
            type="button"
            className="account-nudge__cta"
            onClick={() => navigate('/settings/general')}
          >
            {t('app.nudgeCta')}
          </button>
          <button
            type="button"
            className="account-nudge__dismiss"
            aria-label={t('app.dismiss')}
            onClick={() => { localStorage.setItem('saveAccountNudgeDismissed', '1'); setNudgeDismissed(true); }}
          >
            ×
          </button>
        </div>
      )}

      {celebrations.map(key => (
        <BalloonCelebration
          key={key}
          onDone={() => setCelebrations(prev => prev.filter(k => k !== key))}
        />
      ))}
    </>
  );
}
