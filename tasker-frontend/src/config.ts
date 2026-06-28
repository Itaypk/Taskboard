/**
 * App-wide config baked into the bundle at build time.
 *
 * Override `SUPPORT_EMAIL` by setting the `VITE_SUPPORT_EMAIL` env var when the frontend is built
 * (the Gradle `buildFrontend` task runs `npm run build`). When unset, the default below applies —
 * it matches the public contact address in the Terms of Service.
 */
export const SUPPORT_EMAIL = import.meta.env.VITE_SUPPORT_EMAIL ?? 'hello@backlog.fyi';
