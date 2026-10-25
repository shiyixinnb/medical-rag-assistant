import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 想让它自动开浏览器就改成 open: true
    open: false,
    // 关键：把 /api 代理到 Spring Boot，前端代码里直接写 /api/xxx 即可，无需处理跨域
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1000
  }
})
