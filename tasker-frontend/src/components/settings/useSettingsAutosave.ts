import { useCallback, useEffect, useRef, useState } from 'react';
import { updateUserSettings } from '../../api';
import type { UserSettings } from '../../types';

export type SaveStatus = 'idle' | 'saving' | 'saved' | 'error';

/** The fields `PUT /settings` takes. It replaces all of them, so every save sends the whole set. */
export function settingsPayload(s: UserSettings) {
  return {
    displayName: s.displayName,
    contextBlock: s.contextBlock,
    timeZone: s.timeZone,
    preferredLanguage: s.preferredLanguage,
    appReminders: s.appReminders,
    gender: s.gender,
    agentDescription: s.agentDescription,
    planningCron: s.planningCron ?? null,
    weekStartDay: s.weekStartDay ?? null,
    autoArchiveDays: s.autoArchiveDays ?? null,
    aiEnabled: s.aiEnabled,
    aiEnhancedReminders: s.aiEnhancedReminders,
    dailyDigestEnabled: s.dailyDigestEnabled,
    dailyDigestDueTasks: s.dailyDigestDueTasks,
    dailyDigestCron: s.dailyDigestCron,
  };
}

export interface SettingsAutosave {
  form: UserSettings;
  /**
   * Applies a change. Toggles and pickers save at once; text fields pass `{ save: false }` while
   * typing and call `save()` on blur, so a half-typed value never reaches the server.
   */
  update: (patch: Partial<UserSettings>, opts?: { save?: boolean }) => void;
  /** Saves whatever differs from the last saved state; a no-op when nothing does. */
  save: () => Promise<void>;
  /** The last state the server accepted, for reverting a field that can't be saved. */
  lastSaved: () => UserSettings;
  status: SaveStatus;
}

/**
 * Settings save as they change; there is no Save button. Saves run one at a time in the order
 * they were asked for (each sends the full payload, so an older one landing last would undo a
 * newer change), and a save that changes nothing is skipped.
 *
 * `onSaved` hears about every accepted save, with the previous state, so the caller can keep the
 * rest of the app in step (and apply a language change).
 */
export function useSettingsAutosave(
  initial: UserSettings,
  onSaved: (saved: UserSettings, previous: UserSettings) => void,
): SettingsAutosave {
  const [form, setForm] = useState(initial);
  const [status, setStatus] = useState<SaveStatus>('idle');
  const latest = useRef(initial);
  const saved = useRef(initial);
  const queue = useRef<Promise<void>>(Promise.resolve());
  const onSavedRef = useRef(onSaved);
  useEffect(() => { onSavedRef.current = onSaved; }, [onSaved]);

  const save = useCallback(() => {
    queue.current = queue.current.then(async () => {
      const next = latest.current;
      const payload = settingsPayload(next);
      if (JSON.stringify(payload) === JSON.stringify(settingsPayload(saved.current))) return;
      setStatus('saving');
      try {
        await updateUserSettings(payload);
        const previous = saved.current;
        saved.current = next;
        setStatus('saved');
        onSavedRef.current(next, previous);
      } catch (e) {
        // The API layer has already shown the error; the field keeps its value so it can be fixed.
        console.error('Failed to save settings', e);
        setStatus('error');
      }
    });
    return queue.current;
  }, []);

  const update = useCallback((patch: Partial<UserSettings>, opts?: { save?: boolean }) => {
    latest.current = { ...latest.current, ...patch };
    setForm(latest.current);
    if (opts?.save ?? true) void save();
  }, [save]);

  const lastSaved = useCallback(() => saved.current, []);

  return { form, update, save, lastSaved, status };
}
