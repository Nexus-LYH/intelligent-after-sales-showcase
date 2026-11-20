import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath } from 'node:url'
export default defineConfig({ base: '/customer/', plugins: [vue(), tailwindcss()], resolve: { dedupe: ['vue', 'vue-router', 'pinia', 'element-plus'] }, server: {
  host: '127.0.0.1', port: 5180, strictPort: true,
  fs: { allow: [fileURLToPath(new URL('..', import.meta.url))] },
  hmr: { clientPort: 5173, path: '/customer-hmr' },
} })
