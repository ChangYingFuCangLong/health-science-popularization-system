import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { mockApiPlugin } from './mock'

export default defineConfig({
  plugins: [
    vue(),
    tailwindcss(),
    /*
     * 契约 mock（仅 dev）：后端不在线时按 docs/V2/07 契约返回数据，
     * 后端在线则自动让路给 server.proxy，联调行为完全不变。
     * 关闭方式：VITE_MOCK_API=off。详见 mock/index.ts 顶部说明。
     */
    mockApiPlugin(),
    AutoImport({
      imports: ['vue', 'vue-router', 'pinia'],
      resolvers: [ElementPlusResolver()],
      dts: 'src/types/auto-imports.d.ts'
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/types/components.d.ts'
    })
  ],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },

  server: {
    port: 5173,
    // 开发期由 Vite 转发 /api，避免跨域配置
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },

  build: {
    // 不承诺支持 IE，无需为老浏览器降级转译
    target: 'es2020',
    // Vue 全家桶体积较大且被所有页面依赖，单独成 chunk，便于观察首屏体积。
    // Element Plus / ECharts 分别由管理端页面与健康中心引入，
    // 路由懒加载已天然隔离，无需手动分包。
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router', 'pinia']
        }
      }
    }
  },

  test: {
    // 纯函数单测不需要 DOM。jsdom@30 对 node 版本有更严的要求（EBADENGINE 警告），
    // 组件测试引入时再按文件用 // @vitest-environment jsdom 切换
    environment: 'node',
    include: ['tests/**/*.spec.ts'],
    globals: true
  }
})
