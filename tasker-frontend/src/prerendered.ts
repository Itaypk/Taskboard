/**
 * The content page the server rendered into `#root` (`IndexHtmlController` — `/terms`, `/privacy`,
 * `/about`, `/faq`), taken before React replaces it. While the page's chunk loads, its route shows
 * this markup instead of the "Loading…" placeholder, so a direct visit doesn't flash from the text to
 * a placeholder and back.
 */
let captured: { path: string; html: string } | null = null;

/** Call once, before `createRoot` takes over `root`. */
export function capturePrerendered(root: HTMLElement) {
    captured = root.firstElementChild ? { path: window.location.pathname, html: root.innerHTML } : null;
}

/** The server-rendered markup, if it was rendered for `path`. */
export function prerenderedHtml(path: string): string | null {
    return captured?.path === path ? captured.html : null;
}
