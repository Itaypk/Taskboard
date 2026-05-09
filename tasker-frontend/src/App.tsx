import { useState, useMemo, useCallback, useEffect } from 'react';
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
import { PostItNote } from './components/PostItNote';
import { TaskDrawer } from './components/TaskDrawer';
import { SettingsModal } from './components/SettingsModal';
import { BoardFilter } from './components/BoardFilter';
import { CurrentPlanDrawer } from './components/CurrentPlanDrawer';
import { DEFAULT_SETTINGS } from './data';
import { fetchTasks, fetchCategories, fetchUserSettings, fetchTags, fetchCurrentPlan, createTask, updateTask, deleteTask, reorderTask } from './api';
import type { Task, UserSettings, Tag, CurrentPlan, TaskFilter } from './types';
import { Routes, Route } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { LoginPage } from './auth/LoginPage';
import { TermsPage, PrivacyPage } from './auth/PolicyPage';
import pineappleUrl from './assets/pineapple.png';
import './App.css';

function GearIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" aria-hidden>
      <circle cx="9" cy="9" r="2.4" />
      <path d="M9 1.2v1.8M9 15v1.8M1.2 9H3M15 9h1.8M3.4 3.4l1.3 1.3M13.3 13.3l1.3 1.3M3.4 14.6l1.3-1.3M13.3 4.7l1.3-1.3" />
    </svg>
  );
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

function SignOutIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M7 3H3.5v12H7" />
      <path d="M11 12l3-3-3-3" />
      <path d="M14 9H7" />
    </svg>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/terms" element={<TermsPage />} />
      <Route path="/privacy" element={<PrivacyPage />} />
      <Route path="/*" element={<AuthShell />} />
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
  const [tasks, setTasks]             = useState<Task[]>([]);
  const [tags, setTags]               = useState<Tag[]>([]);
  const [settings, setSettings]       = useState<UserSettings>({ ...DEFAULT_SETTINGS, categories: [] });
  const [selectedId, setSelectedId]   = useState<string | null>(null);
  const [isCreating, setIsCreating]   = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [leavingId, setLeavingId]     = useState<string | null>(null);
  const [loading, setLoading]         = useState(true);
  const [error, setError]             = useState<string | null>(null);
  const [draggingId, setDraggingId]   = useState<string | null>(null);
  const [emailVerifiedBanner, setEmailVerifiedBanner] = useState(false);
  const [filter, setFilter]           = useState<TaskFilter>('todo');
  const [currentPlan, setCurrentPlan] = useState<CurrentPlan | null>(null);
  const [planDrawerOpen, setPlanDrawerOpen] = useState(false);

  // Mouse: start drag after 5px to keep clicks alive.
  // Touch: long-press (~200ms) so tap-to-open and finger-scroll still work.
  const sensors = useSensors(
    useSensor(MouseSensor, { activationConstraint: { distance: 5 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 200, tolerance: 5 } }),
  );

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get('emailVerified') === 'true') {
      setEmailVerifiedBanner(true);
      window.history.replaceState({}, '', window.location.pathname);
      setTimeout(() => setEmailVerifiedBanner(false), 5000);
    }
  }, []);

  useEffect(() => {
    Promise.all([fetchTasks('todo'), fetchCategories(), fetchUserSettings(), fetchTags(), fetchCurrentPlan()])
      .then(([loadedTasks, loadedCategories, loadedSettings, loadedTags, loadedPlan]) => {
        setTasks(loadedTasks);
        setTags(loadedTags);
        setCurrentPlan(loadedPlan);
        setSettings(prev => ({
          ...prev,
          ...loadedSettings,
          displayName: loadedSettings.displayName ?? prev.displayName,
          contextBlock: loadedSettings.contextBlock ?? prev.contextBlock,
          categories: loadedCategories,
        }));
      })
      .catch(() => setError('Failed to load data. Is the backend running?'))
      .finally(() => setLoading(false));
  }, []);

  // Refetch tasks when the filter's status dimension changes.
  // 'plan' reuses the open-tasks fetch and applies plan-membership client-side.
  const fetchStatus = filter === 'plan' ? 'todo' : filter;
  useEffect(() => {
    if (loading) return;
    fetchTasks(fetchStatus).then(setTasks).catch(e => console.error('Failed to refetch tasks', e));
  }, [fetchStatus, loading]);

  const drawerOpen   = selectedId !== null || isCreating;
  const selectedTask = tasks.find(t => t.id === selectedId) ?? null;

  const sortedTasks = useMemo(
    () => [...tasks].sort((a, b) => a.sortKey < b.sortKey ? -1 : a.sortKey > b.sortKey ? 1 : 0),
    [tasks],
  );

  const planId = currentPlan?.id ?? null;
  const visibleTasks = useMemo(() => {
    return sortedTasks.filter(t => {
      if (t.id === leavingId) return true;
      switch (filter) {
        case 'todo': return t.status === 'todo';
        case 'done': return t.status === 'done';
        case 'all':  return true;
        case 'plan': return t.status === 'todo' && planId !== null && t.lastScheduledInSessionId === planId;
      }
    });
  }, [sortedTasks, leavingId, filter, planId]);

  const visibleIds = useMemo(() => visibleTasks.map(t => t.id), [visibleTasks]);

  const categoryById = useMemo(
    () => new Map(settings.categories.map(c => [c.id, c])),
    [settings.categories],
  );

  const closeDrawer = () => { setSelectedId(null); setIsCreating(false); };

  const handleSave = async (updated: Omit<Task, 'sortKey'>) => {
    try {
      const { id: _id, createdAt: _ca, ...payload } = updated;
      if (isCreating) {
        const created = await createTask(payload);
        setTasks(prev => [...prev, created]);
      } else {
        const saved = await updateTask(updated.id, payload);
        setTasks(prev => prev.map(t => t.id === saved.id ? saved : t));
      }
      
      // Refetch tags in case a new tag was created
      fetchTags().then(setTags).catch(e => console.error('Failed to refetch tags', e));
      
      closeDrawer();
    } catch (e) {
      console.error('Failed to save task', e);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await deleteTask(id);
      setTasks(prev => prev.filter(t => t.id !== id));
    } catch (e) {
      console.error('Failed to delete task', e);
    }
  };

  const handleMarkDone = useCallback((id: string) => {
    const task = tasks.find(t => t.id === id);
    if (!task) return;
    setLeavingId(id);
    const { id: _id, createdAt: _ca, sortKey: _sk, ...payload } = task;
    updateTask(id, { ...payload, status: 'done' }).then(() => {
      setTimeout(() => {
        setTasks(prev => prev.map(t => t.id === id ? { ...t, status: 'done' } : t));
        setLeavingId(null);
      }, 380);
    }).catch(e => {
      console.error('Failed to mark task done', e);
      setLeavingId(null);
    });
  }, [tasks]);

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

    try {
      await reorderTask(draggedId, afterId, beforeId);
      const fresh = await fetchTasks(fetchStatus);
      setTasks(fresh);
    } catch (e) {
      console.error('Failed to reorder task', e);
      fetchTasks(fetchStatus).then(setTasks).catch(() => {});
    }
  }, [visibleIds, visibleTasks, fetchStatus]);

  const handleDragCancel = () => setDraggingId(null);

  const defaultCategoryId = settings.categories[0]?.id ?? null;

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

  if (error) {
    return <div className="board-wrap"><div className="board board--empty">{error}</div></div>;
  }

  return (
    <>
      <header className="header">
        <span className="logo-tape">Backlog.fyi</span>
        <BoardFilter
          value={filter}
          onChange={setFilter}
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
            aria-label="View this week's plan"
            title="This week's plan"
          >
            <PlanIcon />
          </button>
          <button
            type="button"
            className="icon-btn"
            onClick={() => setSettingsOpen(true)}
            aria-label="Open settings"
            title="Settings"
          >
            <GearIcon />
          </button>
          <button
            type="button"
            className="icon-btn"
            onClick={() => { void onSignOut(); }}
            aria-label="Sign out"
            title="Sign out"
          >
            <SignOutIcon />
          </button>
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
                    onClick={() => { setIsCreating(false); setSelectedId(task.id); }}
                  />
                ))}
              </div>
            </SortableContext>
          </DndContext>
        )}
      </main>

      <img className="pineapple-pet" src={pineappleUrl} alt="" aria-hidden="true" />

      {emailVerifiedBanner && (
        <div className="email-verified-banner" role="status">
          Email verified successfully
        </div>
      )}

      <TaskDrawer
        task={selectedTask}
        isNew={isCreating}
        open={drawerOpen}
        categories={settings.categories}
        availableTags={tags}
        defaultCategoryId={defaultCategoryId}
        onClose={closeDrawer}
        onSave={handleSave}
        onDelete={handleDelete}
        onMarkDone={handleMarkDone}
      />

      <CurrentPlanDrawer
        plan={currentPlan}
        open={planDrawerOpen}
        onClose={() => setPlanDrawerOpen(false)}
        onTaskClick={(taskId) => {
          setPlanDrawerOpen(false);
          setIsCreating(false);
          setSelectedId(taskId);
        }}
      />

      <SettingsModal
        settings={settings}
        tasks={tasks}
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        onSave={setSettings}
        onAccountDeleted={() => { void onSignOut(); }}
      />
    </>
  );
}
