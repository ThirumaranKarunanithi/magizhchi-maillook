import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Every build gets its own file names (a short time stamp is added to each one).
// The site serves scripts and styles as "cache for a year, never re-check", and Vite's content
// hash keeps the SAME name when the code has not changed. Re-uploading then overwrites a file
// that browsers and Hostinger's CDN already hold forever; on 2026-11-11 the CDN caught Meet's
// main script mid-upload, saved it empty, and served a blank page. A name that has never
// existed before cannot be cached wrong.
const stamp = Date.now().toString(36)

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: {
        entryFileNames: `assets/[name]-[hash]-${stamp}.js`,
        chunkFileNames: `assets/[name]-[hash]-${stamp}.js`,
        assetFileNames: `assets/[name]-[hash]-${stamp}[extname]`,
      },
    },
  },
  server: {
    port: 5177,
    strictPort: true,
    proxy: {
      '/api': { target: 'http://localhost:8097', changeOrigin: true },
    },
  },
})
