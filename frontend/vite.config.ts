import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],
  publicDir: '../app/business-copilot-app/src/main/resources/static',
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/login': {
        target: 'http://127.0.0.1:8080',
        bypass(request) {
          return request.method === 'GET' ? '/index.html' : undefined
        },
      },
      '/logout': 'http://127.0.0.1:8080',
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 650,
    rollupOptions: {
      output: {
        manualChunks(id) {
          return id.includes('/node_modules/vue') ? 'vue' : undefined
        },
      },
    },
  },
})
