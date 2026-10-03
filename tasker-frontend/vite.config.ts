import { defineConfig } from 'vitest/config'
import type { Plugin } from 'vite'
import react from '@vitejs/plugin-react'

// index.html is a Handlebars template the backend renders (IndexHtmlController): the instance's name,
// URL and inline config. The dev server has no backend in front of it, so fill in the hosted
// defaults here; `null` config makes the SPA fall back to its built-in defaults.
function devIndexPlaceholders(): Plugin {
  return {
    name: 'dev-index-placeholders',
    apply: 'serve',
    transformIndexHtml: html => html
      .replaceAll('{{json appName}}', JSON.stringify('Backlog.fyi'))
      .replaceAll('{{json homeUrl}}', JSON.stringify('/'))
      .replaceAll('{{json config}}', 'null')
      .replaceAll('{{appName}}', 'Backlog.fyi')
      .replaceAll('{{homeUrl}}', '/'),
  }
}

export default defineConfig({
  appType: 'spa',
  plugins: [react(), devIndexPlaceholders()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
    // The suite spends ~56% of its wall time building one jsdom per test file, which starved the
    // heavier component tests into spurious 5s timeouts once the file count reached 20 (roughly
    // half of full-suite runs failed, in a different file each time). `vmThreads` keeps per-file
    // isolation but reuses the worker, and measured 72.8s -> 39.9s; the raised timeout is margin
    // on top, for CI runners smaller than a dev machine.
    pool: 'vmThreads',
    testTimeout: 15000,
  },
})
