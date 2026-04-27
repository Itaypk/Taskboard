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
import { DEFAULT_SETTINGS } from './data';
import { fetchTasks, fetchCategories, fetchUserSettings, createTask, updateTask, deleteTask, reorderTask } from './api';
import type { Task, UserSettings } from './types';
import { useAuth } from './auth/AuthContext';
import { LoginPage } from './auth/LoginPage';
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
  const [settings, setSettings]       = useState<UserSettings>({ ...DEFAULT_SETTINGS, categories: [] });
  const [selectedId, setSelectedId]   = useState<string | null>(null);
  const [isCreating, setIsCreating]   = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [leavingId, setLeavingId]     = useState<string | null>(null);
  const [loading, setLoading]         = useState(true);
  const [error, setError]             = useState<string | null>(null);
  const [draggingId, setDraggingId]   = useState<string | null>(null);

  // Mouse: start drag after 5px to keep clicks alive.
  // Touch: long-press (~200ms) so tap-to-open and finger-scroll still work.
  const sensors = useSensors(
    useSensor(MouseSensor, { activationConstraint: { distance: 5 } }),
    useSensor(TouchSensor, { activationConstraint: { delay: 200, tolerance: 5 } }),
  );

  useEffect(() => {
    Promise.all([fetchTasks(), fetchCategories(), fetchUserSettings()])
      .then(([loadedTasks, loadedCategories, loadedSettings]) => {
        setTasks(loadedTasks);
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

  const drawerOpen   = selectedId !== null || isCreating;
  const selectedTask = tasks.find(t => t.id === selectedId) ?? null;

  const sortedTasks = useMemo(
    () => [...tasks].sort((a, b) => a.sortKey < b.sortKey ? -1 : a.sortKey > b.sortKey ? 1 : 0),
    [tasks],
  );

  const visibleTasks = useMemo(
    () => sortedTasks.filter(t => t.status === 'todo' || t.id === leavingId),
    [sortedTasks, leavingId],
  );

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
      const fresh = await fetchTasks();
      setTasks(fresh);
    } catch (e) {
      console.error('Failed to reorder task', e);
      fetchTasks().then(setTasks).catch(() => {});
    }
  }, [visibleIds, visibleTasks]);

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
        <span className="logo-tape">tasker</span>
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
          <div className="board board--empty">Nothing pinned up. Add your first task.</div>
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
                    onClick={() => { setIsCreating(false); setSelectedId(task.id); }}
                  />
                ))}
              </div>
            </SortableContext>
          </DndContext>
        )}
      </main>

      <img className="pineapple-pet" src={pineappleUrl} alt="" aria-hidden="true" />

      <TaskDrawer
        task={selectedTask}
        isNew={isCreating}
        open={drawerOpen}
        categories={settings.categories}
        defaultCategoryId={defaultCategoryId}
        onClose={closeDrawer}
        onSave={handleSave}
        onDelete={handleDelete}
        onMarkDone={handleMarkDone}
      />

      <SettingsModal
        settings={settings}
        tasks={tasks}
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        onSave={setSettings}
      />
    </>
  );
}
