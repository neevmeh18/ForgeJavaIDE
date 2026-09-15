import { defineConfig } from 'vite';

/**
 * The workbench is a static bundle. In production the Java backend serves it from the same
 * origin as the API, so there is no CORS setup and no second service to run.
 *
 * During direct (non-Docker) development `npm run dev` proxies /api to the backend, which keeps
 * the browser talking to one origin there too — the frontend never learns that the backend
 * might live somewhere else.
 */
export default defineConfig({
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 4096,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.FORGE_BACKEND ?? 'http://localhost:3000',
        changeOrigin: false,
      },
    },
  },
});
