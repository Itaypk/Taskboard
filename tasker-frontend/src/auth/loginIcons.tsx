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

// Google's standard multicolour "G", which its branding guidelines require on a sign-in button.
export function GoogleIcon() {
    return (
        <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
            <path fill="#4285F4" d="M23.5 12.3c0-.8-.1-1.6-.2-2.3H12v4.5h6.4a5.5 5.5 0 01-2.4 3.6v3h3.9c2.3-2.1 3.6-5.2 3.6-8.8z" />
            <path fill="#34A853" d="M12 24c3.2 0 6-1.1 8-2.9l-3.9-3c-1.1.7-2.5 1.2-4.1 1.2-3.1 0-5.8-2.1-6.7-5H1.3v3.1A12 12 0 0012 24z" />
            <path fill="#FBBC05" d="M5.3 14.3a7.2 7.2 0 010-4.6V6.6H1.3a12 12 0 000 10.8l4-3.1z" />
            <path fill="#EA4335" d="M12 4.8c1.8 0 3.3.6 4.6 1.8l3.4-3.4A12 12 0 0012 0 12 12 0 001.3 6.6l4 3.1c.9-2.9 3.6-5 6.7-5z" />
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
