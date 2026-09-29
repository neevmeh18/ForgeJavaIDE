import { defineConfig } from 'vite';









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
