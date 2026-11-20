import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
export default defineConfig({ base: '/staff/', plugins: [vue(), tailwindcss()], server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy: {
  '/api': { target: process.env.IAS_GATEWAY_URL || 'http://127.0.0.1:8080', changeOrigin: false },
  '/customer': { target: 'http://127.0.0.1:5180', changeOrigin: false, ws: true },
} } })
