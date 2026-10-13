import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'
import { createReadStream, cpSync } from 'node:fs'
import { resolve } from 'node:path'
import type { Plugin } from 'vite'

// Serve only PDF.js dependency assets, never user originals. Copy the same assets
// into production output so workers/fonts/CMaps do not depend on an external CDN.
function pdfAssets(): Plugin {
  const source = fileURLToPath(new URL('./node_modules/pdfjs-dist/', import.meta.url))
  let output = ''
  return {
    name: 'local-pdfjs-assets',
    configResolved(config) { if (config.command === 'build') output = resolve(config.root, config.build.outDir, 'pdfjs-assets') },
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const match = /^\/pdfjs-assets\/(cmaps|standard_fonts|wasm)\/([A-Za-z0-9_-][A-Za-z0-9_.-]*\.(?:bcmap|pfb|ttf|wasm|mjs|js))$/.exec((request.url || '').split('?')[0]!)
        if (!match) return next()
        response.setHeader('Content-Type', match[2]!.endsWith('.wasm') ? 'application/wasm' : /\.(mjs|js)$/.test(match[2]!) ? 'text/javascript' : 'application/octet-stream')
        const file = createReadStream(resolve(source, match[1]!, match[2]!))
        file.on('error', () => { response.statusCode = 404; response.end() }); file.pipe(response)
      })
    },
    closeBundle() { if (output) for (const directory of ['cmaps', 'standard_fonts', 'wasm']) cpSync(resolve(source, directory), resolve(output, directory), { recursive: true }) },
  }
}

export default defineConfig(({ mode }) => {
  const repositoryRoot = fileURLToPath(new URL('..', import.meta.url))
  const env = loadEnv(mode, repositoryRoot, '')
  const backendPort = Number(process.env.SERVER_PORT || env.SERVER_PORT || '8080')
  const frontendPort = Number(process.env.FRONTEND_PORT || env.FRONTEND_PORT || '5173')
  return {
    plugins: [vue(), pdfAssets()],
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
