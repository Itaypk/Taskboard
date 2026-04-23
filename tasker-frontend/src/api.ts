import type { Task, Category, Tag } from './types';

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

export const fetchTasks = (): Promise<Task[]> =>
    apiRequest('/tasks');

export const createTask = (payload: Omit<Task, 'id' | 'createdAt'>): Promise<Task> =>
    apiRequest('/tasks', { method: 'POST', ...jsonBody(payload) });

export const updateTask = (id: string, payload: Omit<Task, 'id' | 'createdAt'>): Promise<Task> =>
    apiRequest(`/tasks/${id}`, { method: 'PUT', ...jsonBody(payload) });

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
