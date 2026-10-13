import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

export default defineConfig(({ mode }) => {
  const repositoryRoot = fileURLToPath(new URL('..', import.meta.url))
  const env = loadEnv(mode, repositoryRoot, '')
  const backendPort = Number(process.env.SERVER_PORT || env.SERVER_PORT || '8080')
  const frontendPort = Number(process.env.FRONTEND_PORT || env.FRONTEND_PORT || '5173')
  return {
    plugins: [vue()],
    // Only VITE_* variables can enter browser code; DB_PASSWORD is never exposed.
    envDir: repositoryRoot,
    server: {
      host: '127.0.0.1',
      port: frontendPort,
      strictPort: true,
      proxy: { '/api': { target: `http://127.0.0.1:${backendPort}` } },
    },
    test: {
      environment: 'jsdom',
      include: ['src/**/*.test.ts'],
      restoreMocks: true,
    },
  }
})
