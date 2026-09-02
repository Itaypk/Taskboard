/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Public support/contact address, baked at build time. Falls back to a default in `config.ts`. */
  readonly VITE_SUPPORT_EMAIL?: string;
  /** Dedicated abuse-report address, baked at build time. Falls back to a default in `config.ts`. */
  readonly VITE_ABUSE_EMAIL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
