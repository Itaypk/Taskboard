import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  appType: 'spa',
  plugins: [react()],
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
