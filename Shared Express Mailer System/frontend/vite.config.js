import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 共享快递袋系统（SEMS）前端构建配置。
 *
 * 开发代理指向后端 8090（见《系统设计说明书》11.1：生产由 Nginx 反代 8080，
 * 但本机 8080 被 Burp Suite 占用，后端 application-dev.yml 已改走 8090）。
 * 这样前端始终用同源相对路径 /api 与 /ws 发请求：开发期靠这里的代理，
 * 生产期靠 Nginx，业务代码里不出现任何后端地址——与 9.4 的「生产同域部署、CORS 默认关闭」一致。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: false,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8090',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://127.0.0.1:8090',
        ws: true,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    // echarts 与 element-plus 体积本就偏大，默认 500KB 警告会一直刷屏，提高到 1.5MB
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        // 拆包：把不常变的第三方库单独成块，业务代码更新时用户不必重下整包。
        // 注意：Vite 8 底层是 Rolldown，manualChunks 只接受**函数**形式，
        // 传对象（Rollup 4 的老写法）会直接构建失败：Invalid type: Expected Function but received Object。
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('echarts') || id.includes('zrender')) return 'echarts'
          if (id.includes('element-plus') || id.includes('@element-plus')) return 'element'
          if (id.includes('/vue/') || id.includes('vue-router') || id.includes('pinia')) return 'vue'
          return undefined
        },
      },
    },
  },
})
