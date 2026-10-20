import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // ws: true 让 WebSocket 升级请求也走代理，否则开发环境连不上 /api/v1/realtime/ws
      '/api': { target: 'http://localhost:8080', ws: true }
    }
  }
});
