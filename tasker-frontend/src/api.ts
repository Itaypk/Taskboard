import type { Task, Category, Tag, UserSettings, SettingsOptions, CurrentPlan } from './types';

const BASE = '/api/v1';

export const UNAUTHENTICATED_EVENT = 'auth:unauthenticated';

function getCookie(name: string): string | undefined {
    const match = document.cookie.split('; ').find(c => c.startsWith(`${name}=`));
    return match ? decodeURIComponent(match.slice(name.length + 1)) : undefined;
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const headers = new Headers(options.headers);
    const method = (options.method ?? 'GET').toUpperCase();
    if (method !== 'GET' && method !== 'HEAD') {
        const xsrf = getCookie('XSRF-TOKEN');
        if (xsrf) headers.set('X-XSRF-TOKEN', xsrf);
    }
    const res = await fetch(`${path.startsWith('/') ? path : `${BASE}${path}`}`, {
        ...options,
        headers,
        credentials: 'include',
    });
    if (res.status === 401) {
        window.dispatchEvent(new Event(UNAUTHENTICATED_EVENT));
        throw new Error('unauthenticated');
    }
    if (!res.ok) {
        throw new Error(`HTTP ${res.status} ${res.statusText}: ${path}`);
    }
    if (res.status === 204) return undefined as T;
    return res.json() as Promise<T>;
}

function apiRequest<T>(path: string, options?: RequestInit): Promise<T> {
    return request<T>(`${BASE}${path}`, options);
}

function jsonBody(body: unknown): RequestInit {
    return {
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    };
}

// --- Tasks ---

export type TaskStatusFilter = 'todo' | 'done' | 'all';

export const fetchTasks = (status: TaskStatusFilter = 'todo'): Promise<Task[]> =>
    apiRequest(`/tasks?status=${status}`);

// --- Current plan ---

export const fetchCurrentPlan = async (): Promise<CurrentPlan | null> => {
    const res = await fetch(`${BASE}/plans/current`, { credentials: 'include' });
    if (res.status === 401) {
        window.dispatchEvent(new Event(UNAUTHENTICATED_EVENT));
        throw new Error('unauthenticated');
    }
    if (res.status === 204) return null;
    if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}: /plans/current`);
    return res.json() as Promise<CurrentPlan>;
};

export const createTask = (payload: Omit<Task, 'id' | 'createdAt' | 'sortKey'>): Promise<Task> =>
    apiRequest('/tasks', { method: 'POST', ...jsonBody(payload) });

export const updateTask = (id: string, payload: Omit<Task, 'id' | 'createdAt' | 'sortKey'>): Promise<Task> =>
    apiRequest(`/tasks/${id}`, { method: 'PUT', ...jsonBody(payload) });

export const reorderTask = (id: string, afterId: string | null, beforeId: string | null): Promise<Task> =>
    apiRequest(`/tasks/${id}/reorder`, { method: 'PATCH', ...jsonBody({ afterId, beforeId }) });

export const deleteTask = (id: string): Promise<void> =>
    apiRequest(`/tasks/${id}`, { method: 'DELETE' });

// --- Categories ---

export const fetchCategories = (): Promise<Category[]> =>
    apiRequest('/categories');

export const createCategory = (payload: Omit<Category, 'id'>): Promise<Category> =>
    apiRequest('/categories', { method: 'POST', ...jsonBody(payload) });

export const updateCategory = (id: string, payload: Omit<Category, 'id'>): Promise<Category> =>
    apiRequest(`/categories/${id}`, { method: 'PUT', ...jsonBody(payload) });

export const deleteCategory = (id: string): Promise<void> =>
    apiRequest(`/categories/${id}`, { method: 'DELETE' });

// --- Tags ---

export const fetchTags = (): Promise<Tag[]> =>
    apiRequest('/tags');

// --- User Settings ---

type UserSettingsPayload = Pick<UserSettings, 'displayName' | 'contextBlock' | 'timeZone' | 'preferredLanguage' | 'calendarInviteEmail' | 'gender' | 'assistantName' | 'assistantGender'>;

export const fetchUserSettings = (): Promise<UserSettingsPayload> =>
    apiRequest('/settings');

export const updateUserSettings = (payload: UserSettingsPayload): Promise<UserSettingsPayload> =>
    apiRequest('/settings', { method: 'PUT', ...jsonBody(payload) });

export const fetchSettingsOptions = (): Promise<SettingsOptions> =>
    apiRequest('/settings/options');

export const requestEmailVerification = (email: string): Promise<void> =>
    apiRequest('/settings/email', { method: 'POST', ...jsonBody({ email }) });

// --- Account ---

export const deleteAccount = (): Promise<void> =>
    apiRequest('/account', { method: 'DELETE' });

export const exportAccount = async (): Promise<void> => {
    const res = await fetch(`${BASE}/account/export`, { credentials: 'include' });
    if (!res.ok) throw new Error(`Export failed: ${res.status}`);
    const blob = await res.blob();
    const disposition = res.headers.get('Content-Disposition') ?? '';
    const filenameMatch = disposition.match(/filename="?([^"]+)"?/);
    const filename = filenameMatch?.[1] ?? 'backlog-fyi-export.json';
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
};
