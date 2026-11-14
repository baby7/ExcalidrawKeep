import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 开发时前端跑 5173，后端跑 8080；所有 /api 请求转发给后端。
// 生产环境由 Spring Boot 直接把 vite 产物当静态资源提供，同源，不需要代理。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: "dist",
    // Excalidraw 单个 chunk 就很大，别让构建噪音掩盖真正的警告
    chunkSizeWarningLimit: 4000,
  },
});
