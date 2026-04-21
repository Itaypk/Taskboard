import type { Task, Category, Tag } from './types';

const BASE = '/api/v1';

async function request<T>(path: string, options?: RequestInit): Promise<T> {
    const res = await fetch(`${BASE}${path}`, options);
    if (!res.ok) {
        throw new Error(`HTTP ${res.status} ${res.statusText}: ${path}`);
    }
    if (res.status === 204) return undefined as T;
    return res.json() as Promise<T>;
}

function jsonBody(body: unknown): RequestInit {
    return {
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    };
}

// --- Tasks ---

export const fetchTasks = (): Promise<Task[]> =>
    request('/tasks');

export const createTask = (payload: Omit<Task, 'id' | 'createdAt'>): Promise<Task> =>
    request('/tasks', { method: 'POST', ...jsonBody(payload) });

export const updateTask = (id: string, payload: Omit<Task, 'id' | 'createdAt'>): Promise<Task> =>
    request(`/tasks/${id}`, { method: 'PUT', ...jsonBody(payload) });

export const deleteTask = (id: string): Promise<void> =>
    request(`/tasks/${id}`, { method: 'DELETE' });

// --- Categories ---

export const fetchCategories = (): Promise<Category[]> =>
    request('/categories');

export const createCategory = (payload: Omit<Category, 'id'>): Promise<Category> =>
    request('/categories', { method: 'POST', ...jsonBody(payload) });

export const updateCategory = (id: string, payload: Omit<Category, 'id'>): Promise<Category> =>
    request(`/categories/${id}`, { method: 'PUT', ...jsonBody(payload) });

export const deleteCategory = (id: string): Promise<void> =>
    request(`/categories/${id}`, { method: 'DELETE' });

// --- Tags ---

export const fetchTags = (): Promise<Tag[]> =>
    request('/tags');
