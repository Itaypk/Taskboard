import type { Task, Category, Tag, UserSettings, SettingsOptions, CurrentPlan, TimeSlot } from './types';

const BASE = '/api/v1';

export const UNAUTHENTICATED_EVENT = 'auth:unauthenticated';
export const API_ERROR_EVENT = 'api:error';

export interface ApiErrorDetail {
    message: string;
    status: number;
    path: string;
}

export class ApiError extends Error {
    readonly status: number;
    readonly statusText: string;
    readonly path: string;
    readonly userMessage: string;

    constructor(opts: { status: number; statusText: string; path: string; userMessage: string }) {
        super(`HTTP ${opts.status} ${opts.statusText}: ${opts.path}`);
        this.name = 'ApiError';
        this.status = opts.status;
        this.statusText = opts.statusText;
        this.path = opts.path;
        this.userMessage = opts.userMessage;
    }
}

function defaultMessageFor(status: number, statusText: string): string {
    if (status === 0)   return "Can't reach the server. Check your connection and try again.";
    if (status === 400) return 'That request looks invalid. Please review and try again.';
    if (status === 403) return "You don't have permission to do that.";
    if (status === 404) return "We couldn't find what you were looking for.";
    if (status === 409) return 'That change conflicts with the current state. Reload and try again.';
    if (status === 413) return 'That request is too large.';
    if (status === 422) return 'Some of those values are not valid.';
    if (status === 429) return 'Too many requests. Please wait a moment and try again.';
    if (status >= 500)  return 'The server hit an unexpected error. Please try again in a moment.';
    if (status >= 400)  return statusText || 'The request failed.';
    return statusText || 'Something went wrong.';
}

// Spring's ProblemDetail uses `detail`/`title`; our handlers return `{error: "..."}`.
async function extractMessage(res: Response, fallback: string): Promise<string> {
    const ct = res.headers.get('Content-Type') ?? '';
    if (!ct.includes('json')) return fallback;
    try {
        const body = await res.clone().json() as Record<string, unknown>;
        const candidates = ['detail', 'message', 'error', 'title'];
        for (const key of candidates) {
            const v = body[key];
            if (typeof v === 'string' && v.trim().length > 0) return v;
        }
    } catch {
        /* fall through */
    }
    return fallback;
}

function getCookie(name: string): string | undefined {
    const match = document.cookie.split('; ').find(c => c.startsWith(`${name}=`));
    return match ? decodeURIComponent(match.slice(name.length + 1)) : undefined;
}

function emitError(detail: ApiErrorDetail): void {
    window.dispatchEvent(new CustomEvent<ApiErrorDetail>(API_ERROR_EVENT, { detail }));
}

async function rawFetch(path: string, options: RequestInit = {}): Promise<Response> {
    const headers = new Headers(options.headers);
    const method = (options.method ?? 'GET').toUpperCase();
    if (method !== 'GET' && method !== 'HEAD') {
        const xsrf = getCookie('XSRF-TOKEN');
        if (xsrf) headers.set('X-XSRF-TOKEN', xsrf);
    }
    try {
        return await fetch(path, { ...options, headers, credentials: 'include' });
    } catch {
        // Network failure (offline, DNS, CORS preflight blocked, etc.)
        const message = defaultMessageFor(0, 'Network error');
        emitError({ message, status: 0, path });
        throw new ApiError({ status: 0, statusText: 'Network error', path, userMessage: message });
    }
}

async function handle<T>(res: Response, path: string, opts: { jsonOnEmpty?: T } = {}): Promise<T> {
    if (res.status === 401) {
        window.dispatchEvent(new Event(UNAUTHENTICATED_EVENT));
        // Don't toast — the app flips to the login page, which is the user-visible signal.
        throw new ApiError({
            status: 401,
            statusText: res.statusText,
            path,
            userMessage: 'Your session has expired. Please sign in again.',
        });
    }
    if (!res.ok) {
        const fallback = defaultMessageFor(res.status, res.statusText);
        const message = await extractMessage(res, fallback);
        emitError({ message, status: res.status, path });
        throw new ApiError({ status: res.status, statusText: res.statusText, path, userMessage: message });
    }
    if (res.status === 204) return (opts.jsonOnEmpty as T) ?? (undefined as T);
    return res.json() as Promise<T>;
}

export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const url = path.startsWith('/') ? path : `${BASE}${path}`;
    const res = await rawFetch(url, options);
    return handle<T>(res, url);
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

export type TaskStatusFilter = 'todo' | 'done' | 'all' | 'archived';

export const fetchTasks = (status: TaskStatusFilter = 'todo'): Promise<Task[]> =>
    apiRequest(`/tasks?status=${status}`);

export const checkTaskChanges = (since: string): Promise<{ hasChanges: boolean; checkedAt: string }> =>
    apiRequest(`/tasks/has-changes?since=${encodeURIComponent(since)}`);

// --- Current plan ---

interface RawPlanTaskResponse {
    task: Task;
    slots: TimeSlot[];
    notes?: string | null;
}
interface RawCurrentPlanResponse extends Omit<CurrentPlan, 'tasks'> {
    tasks: RawPlanTaskResponse[];
}

export const fetchCurrentPlan = async (): Promise<CurrentPlan | null> => {
    const path = `${BASE}/plans/current`;
    const res = await rawFetch(path);
    if (res.status === 204) return null;
    const raw = await handle<RawCurrentPlanResponse>(res, path);
    return {
        ...raw,
        tasks: raw.tasks.map(pt => ({
            ...pt.task,
            slots: pt.slots,
            planNotes: pt.notes ?? undefined,
        })),
    };
};

export const createTask = (payload: Omit<Task, 'id' | 'createdAt' | 'sortKey'>): Promise<Task> =>
    apiRequest('/tasks', { method: 'POST', ...jsonBody(payload) });

export const updateTask = (id: string, payload: Omit<Task, 'id' | 'createdAt' | 'sortKey'>): Promise<Task> =>
    apiRequest(`/tasks/${id}`, { method: 'PUT', ...jsonBody(payload) });

export const reorderTask = (id: string, afterId: string | null, beforeId: string | null): Promise<Task> =>
    apiRequest(`/tasks/${id}/reorder`, { method: 'PATCH', ...jsonBody({ afterId, beforeId }) });

export const deleteTask = (id: string): Promise<void> =>
    apiRequest(`/tasks/${id}`, { method: 'DELETE' });

export const removeTaskFromPlan = (id: string): Promise<void> =>
    apiRequest(`/tasks/${id}/plan-schedule`, { method: 'DELETE' });

export const addTaskToPlan = (taskId: string, startIso: string, endIso: string): Promise<void> =>
    apiRequest(`/plans/current/tasks/${taskId}`, { method: 'POST', ...jsonBody({ startIso, endIso }) });

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

type UserSettingsPayload = Pick<UserSettings, 'displayName' | 'contextBlock' | 'timeZone' | 'preferredLanguage' | 'calendarInviteEmail' | 'gender' | 'agentDescription' | 'planningCron' | 'weekStartDay' | 'autoArchiveDays'>;

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
    const path = `${BASE}/account/export`;
    const res = await rawFetch(path);
    if (!res.ok) {
        await handle<void>(res, path);
        return;
    }
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

export interface ImportSummary {
    categories: number;
    tags: number;
    tasks: number;
}

export const importAccount = (payload: unknown): Promise<ImportSummary> =>
    apiRequest('/account/import', { method: 'POST', ...jsonBody(payload) });
