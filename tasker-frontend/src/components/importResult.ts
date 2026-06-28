import { ApiError, type ImportSummary } from '../api';

/** Mirrors the backend's `ImportErrorCategory` (`service/ImportException.kt`). */
export type ImportErrorCategory =
  | 'CORRUPTED_FILE'
  | 'UNSUPPORTED_VERSION'
  | 'ACCOUNT_NOT_EMPTY'
  | 'INTERNAL_ERROR';

export type ImportResult =
  | { kind: 'success'; summary: ImportSummary }
  | { kind: 'error'; category: ImportErrorCategory; detail?: string };

const KNOWN_CATEGORIES: readonly ImportErrorCategory[] = [
  'CORRUPTED_FILE', 'UNSUPPORTED_VERSION', 'ACCOUNT_NOT_EMPTY', 'INTERNAL_ERROR',
];

/**
 * Maps a thrown import error to a category + a technical detail line. Prefers the backend's stable
 * `code`; falls back to the HTTP status (a 400 means a bad request body → corrupted file) and finally
 * to a generic internal error for network failures and unknowns.
 */
export function categorizeImportError(err: unknown): { category: ImportErrorCategory; detail?: string } {
  if (err instanceof ApiError) {
    if (err.code && (KNOWN_CATEGORIES as string[]).includes(err.code)) {
      return { category: err.code as ImportErrorCategory, detail: err.userMessage };
    }
    if (err.status === 400) return { category: 'CORRUPTED_FILE', detail: err.userMessage };
    return { category: 'INTERNAL_ERROR', detail: err.userMessage };
  }
  return { category: 'INTERNAL_ERROR', detail: err instanceof Error ? err.message : undefined };
}
