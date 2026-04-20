import { useState, useMemo, useCallback } from 'react';
import { PostItNote } from './components/PostItNote';
import { TaskDrawer } from './components/TaskDrawer';
import { SettingsModal } from './components/SettingsModal';
import { MOCK_TASKS, DEFAULT_SETTINGS } from './data';
import type { Task, UserSettings } from './types';
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

export default function App() {
  const [tasks, setTasks]             = useState<Task[]>(MOCK_TASKS);
  const [settings, setSettings]       = useState<UserSettings>(DEFAULT_SETTINGS);
  const [selectedId, setSelectedId]   = useState<string | null>(null);
  const [isCreating, setIsCreating]   = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [leavingId, setLeavingId]     = useState<string | null>(null);

  const drawerOpen   = selectedId !== null || isCreating;
  const selectedTask = tasks.find(t => t.id === selectedId) ?? null;

  const visibleTasks = useMemo(
    () => tasks.filter(t => t.status === 'todo' || t.id === leavingId),
    [tasks, leavingId],
  );

  const categoryById = useMemo(
    () => new Map(settings.categories.map(c => [c.id, c])),
    [settings.categories],
  );

  const closeDrawer = () => { setSelectedId(null); setIsCreating(false); };

  const handleSave = (updated: Task) => {
    setTasks(prev => {
      const exists = prev.some(t => t.id === updated.id);
      return exists
        ? prev.map(t => (t.id === updated.id ? updated : t))
        : [updated, ...prev];
    });
    closeDrawer();
  };

  const handleDelete = (id: string) => {
    setTasks(prev => prev.filter(t => t.id !== id));
  };

  const handleMarkDone = useCallback((id: string) => {
    setLeavingId(id);
    setTimeout(() => {
      setTasks(prev => prev.map(t => t.id === id ? { ...t, status: 'done' } : t));
      setLeavingId(null);
    }, 380);
  }, []);

  const defaultCategoryId = settings.categories[0]?.id ?? null;

  const openNew = () => {
    if (!defaultCategoryId) return;
    setSelectedId(null);
    setIsCreating(true);
  };

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
        </div>
      </header>

      <main className="board-wrap">
        {visibleTasks.length === 0 ? (
          <div className="board board--empty">Nothing pinned up. Add your first task.</div>
        ) : (
          <div className="board">
            {visibleTasks.map((task, i) => (
              <PostItNote
                key={task.id}
                task={task}
                category={categoryById.get(task.categoryId)}
                index={i}
                leaving={leavingId === task.id}
                onClick={() => { setIsCreating(false); setSelectedId(task.id); }}
              />
            ))}
          </div>
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
