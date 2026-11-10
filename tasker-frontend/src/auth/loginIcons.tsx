// Sign-in method icons, shared by the landing page's login modal and the in-app "save my account"
// modal. Kept apart from LoginPage so the board chunk can import them without pulling the page in.

export function TelegramIcon() {
    return (
        <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="12" fill="#2aabee" />
            <path
                fill="#fff"
                d="M5.5 11.9l11-4.25c.51-.18.96.12.79.9l-1.87 8.82c-.13.62-.5.77-1.02.48l-2.82-2.08-1.36 1.31c-.15.15-.28.28-.57.28l.2-2.88 5.25-4.74c.23-.2-.05-.32-.35-.12l-6.49 4.08-2.8-.87c-.6-.19-.62-.6.13-.89z"
            />
        </svg>
    );
}

export function MailIcon() {
    return (
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <rect x="3" y="5" width="18" height="14" rx="2.5" />
            <path d="M4 7l8 6 8-6" />
        </svg>
    );
}
