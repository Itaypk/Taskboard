/**
 * App-wide config baked into the bundle at build time.
 *
 * Override `SUPPORT_EMAIL` by setting the `VITE_SUPPORT_EMAIL` env var when the frontend is built
 * (the Gradle `buildFrontend` task runs `npm run build`). When unset, the default below applies —
 * it matches the public contact address in the Terms of Service.
 */
export const SUPPORT_EMAIL = import.meta.env.VITE_SUPPORT_EMAIL ?? 'hello@backlog.fyi';

/**
 * Dedicated abuse-report contact address, baked at build time the same way as `SUPPORT_EMAIL`.
 * Override with `VITE_ABUSE_EMAIL`; falls back to the address referenced in the ToS/Privacy Policy.
 */
export const ABUSE_EMAIL = import.meta.env.VITE_ABUSE_EMAIL ?? 'abuse@backlog.fyi';
