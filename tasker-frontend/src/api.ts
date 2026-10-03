import i18n from './i18n';
import type { Task, Category, Tag, UserSettings, SettingsOptions, CurrentPlan, OneOffEvent, TimeSlot, Stats, AiUsage } from './types';

const BASE = '/api/v1';

export const UNAUTHENTICATED_EVENT = 'auth:unauthenticated';
export const API_ERROR_EVENT = 'api:error';

export interface ApiErrorDetail {
    message: string;
    status: number;
    path: string;
}

/** Per-call client behavior, separate from fetch's `RequestInit`. */
export interface RequestConfig {
    /**
     * When false, suppress the global error toast/event on failure — the caller takes full
     * responsibility for surfacing the error itself (the rejected `ApiError` still propagates).
     * Defaults to true.
     */
    emitErrors?: boolean;
}

/** A single field-level validation failure, from a ProblemDetail's `errors` extension property. */
export interface ApiFieldError {
    field: string;
    message: string;
}

export class ApiError extends Error {
    readonly status: number;
    readonly statusText: string;
    readonly path: string;
    readonly userMessage: string;
    /** Per-field reasons for a 400 validation failure, when the server sent a ProblemDetail with `errors`. */
    readonly fieldErrors?: ApiFieldError[];
    /** Stable machine-readable error code, when the server sent one (e.g. import `code`). */
    readonly code?: string;

    constructor(opts: { status: number; statusText: string; path: string; userMessage: string; fieldErrors?: ApiFieldError[]; code?: string }) {
        super(`HTTP ${opts.status} ${opts.statusText}: ${opts.path}`);
        this.name = 'ApiError';
        this.status = opts.status;
        this.statusText = opts.statusText;
        this.path = opts.path;
        this.userMessage = opts.userMessage;
        this.fieldErrors = opts.fieldErrors;
        this.code = opts.code;
    }
}

function defaultMessageFor(status: number, statusText: string): string {
    if (status === 0)   return i18n.t('api.errors.network');
    if (status === 400) return i18n.t('api.errors.badRequest');
    if (status === 403) return i18n.t('api.errors.forbidden');
    if (status === 404) return i18n.t('api.errors.notFound');
    if (status === 409) return i18n.t('api.errors.conflict');
    if (status === 413) return i18n.t('api.errors.payloadTooLarge');
    if (status === 422) return i18n.t('api.errors.unprocessable');
    if (status === 429) return i18n.t('api.errors.rateLimited');
    if (status >= 500)  return i18n.t('api.errors.serverError');
    if (status >= 400)  return statusText || i18n.t('api.errors.requestFailed');
    return statusText || i18n.t('api.errors.generic');
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

// Our endpoints may include a stable machine-readable `code` alongside the human `error` message.
async function extractCode(res: Response): Promise<string | undefined> {
    const ct = res.headers.get('Content-Type') ?? '';
    if (!ct.includes('json')) return undefined;
    try {
        const body = await res.clone().json() as Record<string, unknown>;
        return typeof body.code === 'string' && body.code.length > 0 ? body.code : undefined;
    } catch {
        return undefined;
    }
}

// RFC 7807 ProblemDetail carries our per-field validation reasons in the `errors` extension property.
async function extractFieldErrors(res: Response): Promise<ApiFieldError[] | undefined> {
    const ct = res.headers.get('Content-Type') ?? '';
    if (!ct.includes('json')) return undefined;
    try {
        const body = await res.clone().json() as { errors?: unknown };
        if (!Array.isArray(body.errors)) return undefined;
        const parsed = body.errors.filter((e): e is ApiFieldError =>
            typeof e === 'object' && e !== null &&
            typeof (e as ApiFieldError).field === 'string' &&
            typeof (e as ApiFieldError).message === 'string',
        );
        return parsed.length > 0 ? parsed : undefined;
    } catch {
        return undefined;
    }
}

function getCookie(name: string): string | undefined {
    const match = document.cookie.split('; ').find(c => c.startsWith(`${name}=`));
    return match ? decodeURIComponent(match.slice(name.length + 1)) : undefined;
}

function emitError(detail: ApiErrorDetail): void {
    window.dispatchEvent(new CustomEvent<ApiErrorDetail>(API_ERROR_EVENT, { detail }));
}

/**
 * Surfaces a client-side message through the same global toast channel API errors use. For flows
 * that want a friendlier, action-specific message than the generic status fallback (pair with
 * `{ emitErrors: false }` on the request so only this message shows).
 */
export function notifyError(message: string): void {
    emitError({ message, status: 0, path: '' });
}

async function rawFetch(path: string, options: RequestInit = {}, emitErrors = true): Promise<Response> {
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
        if (emitErrors) emitError({ message, status: 0, path });
        throw new ApiError({ status: 0, statusText: 'Network error', path, userMessage: message });
    }
}

async function handle<T>(res: Response, path: string, opts: { jsonOnEmpty?: T; emitErrors?: boolean } = {}): Promise<T> {
    const emitErrors = opts.emitErrors ?? true;
    if (res.status === 401) {
        window.dispatchEvent(new Event(UNAUTHENTICATED_EVENT));
        // Don't toast — the app flips to the login page, which is the user-visible signal.
        throw new ApiError({
            status: 401,
            statusText: res.statusText,
            path,
            userMessage: i18n.t('api.errors.sessionExpired'),
        });
    }
    if (!res.ok) {
        const fallback = defaultMessageFor(res.status, res.statusText);
        const message = await extractMessage(res, fallback);
        const fieldErrors = await extractFieldErrors(res);
        const code = await extractCode(res);
        if (emitErrors) emitError({ message, status: res.status, path });
        throw new ApiError({ status: res.status, statusText: res.statusText, path, userMessage: message, fieldErrors, code });
    }
    if (res.status === 204) return (opts.jsonOnEmpty as T) ?? (undefined as T);
    return res.json() as Promise<T>;
}

export async function request<T>(path: string, options: RequestInit = {}, config: RequestConfig = {}): Promise<T> {
    const url = path.startsWith('/') ? path : `${BASE}${path}`;
    const emitErrors = config.emitErrors ?? true;
    const res = await rawFetch(url, options, emitErrors);
    return handle<T>(res, url, { emitErrors });
}

function apiRequest<T>(path: string, options?: RequestInit, config?: RequestConfig): Promise<T> {
    return request<T>(`${BASE}${path}`, options, config);
}

function jsonBody(body: unknown): RequestInit {
    return {
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    };
}

// --- Boards ---

export interface Board {
    id: string;
    name: string;
    role: 'OWNER' | 'MEMBER';
    createdAt: string;
    /** Members on the board; > 1 means it's shared. */
    memberCount: number;
    /** Cosmetic mascot id (see `mascots.ts`); always a known id. */
    mascot: string;
}

/** The user's boards, default board first (the backend orders by membership age). */
export const fetchBoards = (): Promise<Board[]> =>
    apiRequest('/boards');

export const createBoard = (name: string): Promise<Board> =>
    apiRequest('/boards', { method: 'POST', ...jsonBody({ name }) });

/** Copies a board's categories, tags, and tasks into a new board owned solely by the caller. */
export const duplicateBoard = (boardId: string, name: string, resetTaskStatus: boolean): Promise<Board> =>
    apiRequest(`/boards/${boardId}/duplicate`, { method: 'POST', ...jsonBody({ name, resetTaskStatus }) });

/** Updates a board's name and/or mascot (owner only). Omitting `mascot` leaves it unchanged. */
export const updateBoard = (boardId: string, payload: { name: string; mascot?: string }): Promise<Board> =>
    apiRequest(`/boards/${boardId}`, { method: 'PATCH', ...jsonBody(payload) });

export const deleteBoard = (boardId: string): Promise<void> =>
    apiRequest(`/boards/${boardId}`, { method: 'DELETE' });

// --- Members & invitations ---

export interface BoardMember {
    userId: string;
    role: 'OWNER' | 'MEMBER';
    joinedAt: string;
    displayName: string;
}

export interface PendingInvitation {
    id: string;
    createdAt: string;
    expiresAt: string;
}

export interface InvitationPreview {
    boardName: string;
    inviterName: string;
    expiresAt: string;
}

export interface AcceptedInvitation {
    boardId: string;
    boardName: string;
}

export const fetchMembers = (boardId: string): Promise<BoardMember[]> =>
    apiRequest(`/boards/${boardId}/members`);

export const setMemberRole = (boardId: string, userId: string, role: 'OWNER' | 'MEMBER'): Promise<void> =>
    apiRequest(`/boards/${boardId}/members/${userId}`, { method: 'PATCH', ...jsonBody({ role }) });

export const removeMember = (boardId: string, userId: string): Promise<void> =>
    apiRequest(`/boards/${boardId}/members/${userId}`, { method: 'DELETE' });

export const leaveBoard = (boardId: string): Promise<void> =>
    apiRequest(`/boards/${boardId}/members/leave`, { method: 'POST' });

export const fetchInvitations = (boardId: string): Promise<PendingInvitation[]> =>
    apiRequest(`/boards/${boardId}/invitations`);

export const inviteToBoard = (boardId: string, email: string): Promise<PendingInvitation> =>
    apiRequest(`/boards/${boardId}/invitations`, { method: 'POST', ...jsonBody({ email }) });

export const revokeInvitation = (boardId: string, invitationId: string): Promise<void> =>
    apiRequest(`/boards/${boardId}/invitations/${invitationId}`, { method: 'DELETE' });

export const fetchInvitationPreview = (token: string): Promise<InvitationPreview> =>
    apiRequest(`/invitations/${token}`);

export const acceptInvitation = (token: string): Promise<AcceptedInvitation> =>
    apiRequest(`/invitations/${token}/accept`, { method: 'POST' });

// --- Tasks ---

export type TaskStatusFilter = 'todo' | 'done' | 'all' | 'archived';

export const fetchTasks = (boardId: string, status: TaskStatusFilter = 'todo'): Promise<Task[]> =>
    apiRequest(`/boards/${boardId}/tasks?status=${status}`);

// --- Sync ---

/**
 * One-shot staleness snapshot for an open board tab. Each `*ChangedAt` is the watermark for that
 * entity type (or null if nothing recorded); the caller keeps the last-seen values and refetches only
 * what moved. `appVersion` lets the SPA notice a backend redeploy and prompt a refresh.
 */
export interface SyncSnapshot {
    checkedAt: string;
    tasksChangedAt: string | null;
    tagsChangedAt: string | null;
    categoriesChangedAt: string | null;
    planChangedAt: string | null;
    appVersion: string;
}

export const fetchSync = (boardId: string): Promise<SyncSnapshot> =>
    apiRequest(`/boards/${boardId}/sync`);

// --- Current plan ---

interface RawPlanTaskResponse {
    task: Task;
    slots: TimeSlot[];
    notes?: string | null;
}
interface RawCurrentPlanResponse extends Omit<CurrentPlan, 'tasks'> {
    tasks: RawPlanTaskResponse[];
}

const mapPlan = (raw: RawCurrentPlanResponse): CurrentPlan => ({
    ...raw,
    tasks: raw.tasks.map(pt => ({
        ...pt.task,
        slots: pt.slots,
        planNotes: pt.notes ?? undefined,
    })),
});

const fetchPlanAt = async (path: string): Promise<CurrentPlan | null> => {
    const res = await rawFetch(path);
    if (res.status === 204) return null;
    return mapPlan(await handle<RawCurrentPlanResponse>(res, path));
};

/** The finalized plan for the week containing today (the board's anchor), or null if unplanned. */
export const fetchCurrentPlan = (): Promise<CurrentPlan | null> =>
    fetchPlanAt(`${BASE}/plans/current`);

/** The finalized plan for a specific week (ISO week-start date), or null if that week was never planned. */
export const fetchPlanForWeek = (weekStart: string): Promise<CurrentPlan | null> =>
    fetchPlanAt(`${BASE}/plans/week/${weekStart}`);

/** One-off events whose start falls in the user's local ISO week beginning at [weekStart]. Independent of plan presence. */
export const fetchEventsForWeek = (weekStart: string): Promise<OneOffEvent[]> =>
    apiRequest(`/plans/week/${weekStart}/events`);

/** Cancels a not-yet-started one-off event (soft-delete + calendar cancellation email). 409 if it already started. */
export const cancelEvent = (eventId: string): Promise<void> =>
    apiRequest(`/plans/events/${eventId}/cancel`, { method: 'POST' });

/** Lightweight summary of a finalized weekly plan, used to drive the drawer's week navigation. */
export interface PlanSummary {
    id: string;
    weekStart: string;
    weekEnd: string;
    status: string;
    taskCount: number;
    hasSummary: boolean;
}

/** Index of the user's finalized plans, most recent week first. */
export const fetchPlans = (): Promise<PlanSummary[]> =>
    apiRequest('/plans');

export const createTask = (boardId: string, payload: Omit<Task, 'id' | 'createdAt' | 'sortKey'>, config?: RequestConfig): Promise<Task> =>
    apiRequest(`/boards/${boardId}/tasks`, { method: 'POST', ...jsonBody(payload) }, config);

export const updateTask = (boardId: string, id: string, payload: Omit<Task, 'id' | 'createdAt' | 'sortKey'>, config?: RequestConfig): Promise<Task> =>
    apiRequest(`/boards/${boardId}/tasks/${id}`, { method: 'PUT', ...jsonBody(payload) }, config);

export const reorderTask = (boardId: string, id: string, afterId: string | null, beforeId: string | null): Promise<Task> =>
    apiRequest(`/boards/${boardId}/tasks/${id}/reorder`, { method: 'PATCH', ...jsonBody({ afterId, beforeId }) });

export const deleteTask = (boardId: string, id: string): Promise<void> =>
    apiRequest(`/boards/${boardId}/tasks/${id}`, { method: 'DELETE' });

/** Copies a task on the same board; the copy is a fresh TODO task with a " (copy)" title suffix. */
export const duplicateTask = (boardId: string, id: string): Promise<Task> =>
    apiRequest(`/boards/${boardId}/tasks/${id}/duplicate`, { method: 'POST' });

/** Moves a task to another board the user belongs to, into the given category; returns the moved task. */
export const moveTaskToBoard = (boardId: string, id: string, targetBoardId: string, categoryId: string): Promise<Task> =>
    apiRequest(`/boards/${boardId}/tasks/${id}/move`, { method: 'POST', ...jsonBody({ targetBoardId, categoryId }) });

export const removeTaskFromPlan = (boardId: string, id: string): Promise<void> =>
    apiRequest(`/boards/${boardId}/tasks/${id}/plan-schedule`, { method: 'DELETE' });

/** Clears the seeded tutorial backlog for a board in one shot. */
export const clearTutorialTasks = (boardId: string): Promise<void> =>
    apiRequest(`/boards/${boardId}/tasks/tutorial`, { method: 'DELETE' });

/** Claim/unclaim or assign a task. `userId` null clears the assignee. */
export const setTaskAssignee = (boardId: string, id: string, userId: string | null): Promise<Task> =>
    apiRequest(`/boards/${boardId}/tasks/${id}/assignee`, { method: 'PUT', ...jsonBody({ userId }) });

export const addTaskToPlan = (taskId: string, startIso: string, endIso: string, config?: RequestConfig): Promise<void> =>
    apiRequest(`/plans/current/tasks/${taskId}`, { method: 'POST', ...jsonBody({ startIso, endIso }) }, config);

/** Reschedules an already-planned task to a new single slot in the current plan. */
export const changeTaskSlot = (taskId: string, startIso: string, endIso: string, config?: RequestConfig): Promise<void> =>
    apiRequest(`/plans/current/tasks/${taskId}/slot`, { method: 'PUT', ...jsonBody({ startIso, endIso }) }, config);

// --- Categories ---

export const fetchCategories = (boardId: string): Promise<Category[]> =>
    apiRequest(`/boards/${boardId}/categories`);

export const createCategory = (boardId: string, payload: Omit<Category, 'id'>): Promise<Category> =>
    apiRequest(`/boards/${boardId}/categories`, { method: 'POST', ...jsonBody(payload) });

export const updateCategory = (boardId: string, id: string, payload: Omit<Category, 'id'>): Promise<Category> =>
    apiRequest(`/boards/${boardId}/categories/${id}`, { method: 'PUT', ...jsonBody(payload) });

export const deleteCategory = (boardId: string, id: string): Promise<void> =>
    apiRequest(`/boards/${boardId}/categories/${id}`, { method: 'DELETE' });

// --- Tags ---

export const fetchTags = (boardId: string): Promise<Tag[]> =>
    apiRequest(`/boards/${boardId}/tags`);

export const updateTag = (boardId: string, id: string, payload: { label: string; colorId: string }): Promise<Tag> =>
    apiRequest(`/boards/${boardId}/tags/${id}`, { method: 'PUT', ...jsonBody(payload) });

export const deleteTag = (boardId: string, id: string): Promise<void> =>
    apiRequest(`/boards/${boardId}/tags/${id}`, { method: 'DELETE' });

// --- User Settings ---

type UserSettingsPayload = Pick<UserSettings, 'displayName' | 'contextBlock' | 'timeZone' | 'preferredLanguage' | 'calendarInviteEmail' | 'appReminders' | 'gender' | 'agentDescription' | 'planningCron' | 'weekStartDay' | 'autoArchiveDays' | 'aiEnabled' | 'aiEnhancedReminders' | 'dailyDigestEnabled' | 'dailyDigestDueTasks' | 'dailyDigestCron'>;

export const fetchUserSettings = (): Promise<UserSettingsPayload> =>
    apiRequest('/settings');

export const updateUserSettings = (payload: UserSettingsPayload): Promise<UserSettingsPayload> =>
    apiRequest('/settings', { method: 'PUT', ...jsonBody(payload) });

export const fetchSettingsOptions = (): Promise<SettingsOptions> =>
    apiRequest('/settings/options');

export const fetchAiUsage = (): Promise<AiUsage> =>
    apiRequest('/settings/ai-usage');

/** Turns daily-digest reminders back on for every due task the user muted. */
export const clearDeadlineMutes = (): Promise<{ cleared: number }> =>
    apiRequest('/settings/deadline-mutes', { method: 'DELETE' });

export const requestEmailVerification = (email: string): Promise<void> =>
    apiRequest('/settings/email', { method: 'POST', ...jsonBody({ email }) });

// --- Feedback ---

/** Sends a piece of user feedback. `replyEmail` is optional — only if the user wants a reply. */
export const sendFeedback = (message: string, replyEmail?: string, config?: RequestConfig): Promise<void> =>
    apiRequest('/feedback', { method: 'POST', ...jsonBody({ message, replyEmail: replyEmail || null }) }, config);

// --- Weekly planning (web channel) ---

export interface RenderedChoiceOption {
    id: string;
    label: string;
}

export interface RenderedMessage {
    type: 'text' | 'choice';
    text: string;
    completions: string[];
    options: RenderedChoiceOption[];
}

export interface PlanningTurn {
    sessionId: string;
    phase: string;
    messages: RenderedMessage[];
}

export interface WeekOption {
    weekStart: string;
    weekEnd: string;
}

export interface PlanningEntry {
    activeSessionId: string | null;
    completedPlanSummary: string | null;
    revisableSessionId: string | null;
    thisWeek: WeekOption;
    nextWeek: WeekOption;
    /** False when AI is opted out (by the user or by a co-member on every board) — disable planning controls. */
    aiAvailable: boolean;
    /** Backlog tasks the planner could actually schedule. 0 = starting a session would plan nothing. */
    plannableTaskCount: number;
}

export interface TranscriptMessage {
    role: 'user' | 'assistant';
    type: 'text' | 'choice';
    text: string;
    completions: string[];
    options: RenderedChoiceOption[];
}

export interface PlanningTranscript {
    sessionId: string;
    phase: string;
    messages: TranscriptMessage[];
}

export const fetchPlanningEntry = (): Promise<PlanningEntry> =>
    apiRequest('/planning/entry');

export const fetchPlanningTranscript = (sessionId: string): Promise<PlanningTranscript> =>
    apiRequest(`/planning/${sessionId}`);

export const startPlanning = (offset: 'CURRENT' | 'NEXT'): Promise<PlanningTurn> =>
    apiRequest('/planning/start', { method: 'POST', ...jsonBody({ offset }) });

export const replyPlanning = (sessionId: string, body: { text?: string; optionId?: string }): Promise<PlanningTurn> =>
    apiRequest(`/planning/${sessionId}/reply`, { method: 'POST', ...jsonBody(body) });

export const revisePlanning = (sessionId: string): Promise<PlanningTurn> =>
    apiRequest(`/planning/${sessionId}/revise`, { method: 'POST' });

export const abandonPlanning = (sessionId: string): Promise<void> =>
    apiRequest(`/planning/${sessionId}/abandon`, { method: 'POST' });

// --- Stats ---

export const fetchStats = (): Promise<Stats> =>
    apiRequest('/stats');

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
    /** Whether the exported email was adopted onto this account. */
    emailImported: boolean;
    /** Stable code when the email was skipped: 'ACCOUNT_HAS_EMAIL' | 'TAKEN'. Null otherwise. */
    emailSkipReason?: string | null;
}

export const importAccount = (payload: unknown): Promise<ImportSummary> =>
    // Suppress the global error toast: the import result dialog surfaces success and failure itself,
    // so a toast would just duplicate (and out-shout) the dialog's detailed message.
    apiRequest('/account/import', { method: 'POST', ...jsonBody(payload) }, { emitErrors: false });

// --- Agent API tokens ---

export type ApiTokenScope = 'read' | 'write';

export interface ApiToken {
    id: string;
    name: string;
    /** Non-secret leading fragment, e.g. `blf_a1b2c3d4` — enough to tell two tokens apart. */
    prefix: string;
    scope: ApiTokenScope;
    createdAt: string;
    lastUsedAt: string | null;
    expiresAt: string | null;
}

export interface CreatedApiToken {
    /** The plaintext secret. Returned only here, only once — it is never recoverable. */
    token: string;
    apiToken: ApiToken;
}

export const fetchApiTokens = (): Promise<ApiToken[]> =>
    apiRequest('/api-tokens');

export const createApiToken = (name: string, scope: ApiTokenScope): Promise<CreatedApiToken> =>
    apiRequest('/api-tokens', { method: 'POST', ...jsonBody({ name, scope }) });

export const revokeApiToken = (id: string): Promise<void> =>
    apiRequest(`/api-tokens/${id}`, { method: 'DELETE' });
