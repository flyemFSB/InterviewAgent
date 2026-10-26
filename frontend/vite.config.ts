import {defineConfig} from 'vite-plus'
// loadEnv 不从 vite-plus 导出（vite-plus 只导出 defineConfig/defineProject/lazyPlugins），
// 但 'vite' 说明符在 Vite+ 项目里会走 @voidzero-dev/vite-plus-core 别名，因此仍可解析。
import {loadEnv} from 'vite'
import react, {reactCompilerPreset} from '@vitejs/plugin-react'
import babel from '@rolldown/plugin-babel'

// https://viteplus.dev/guide/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiProxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [
      react(),
      // React Compiler：Vite 8 / Rolldown 下走官方 Babel 路径
      // （@vitejs/plugin-react v6 提供 reactCompilerPreset，@rolldown/plugin-babel 执行）
      babel({presets: [reactCompilerPreset()]}),
    ],
    build: {
      // Vite 8（Rolldown）移除了 rollupOptions.output.manualChunks 的对象写法，
      // 改用 Rolldown 的 codeSplitting.groups（test 正则 + name）。
      rolldownOptions: {
        output: {
          codeSplitting: {
            groups: [
              {name: 'react-vendor', test: /node_modules\/(react|react-dom|scheduler|react-router|@tanstack)\//},
              {name: 'ui-vendor', test: /node_modules\/(framer-motion|lucide-react|@base-ui|react-big-calendar|recharts)\//},
              {name: 'syntax-highlighter', test: /node_modules\/(react-syntax-highlighter|refractor|prismjs|highlight\.js)\//},
            ],
          },
        },
      },
    },
    server: {
      host: '0.0.0.0',
      port: 5173,
      proxy: {
        '/ws': {
          target: apiProxyTarget,
          ws: true,
          changeOrigin: false,
        },
        '/api': {
          target: apiProxyTarget,
          // 保留浏览器的 Host，与 Origin 一致，避免同源上传被后端误判为跨域。
          changeOrigin: false,
          configure(proxy) {
            proxy.on('error', (_error, _request, response) => {
              if (!response || !('writeHead' in response) || response.headersSent || response.writableEnded) return;
              response.writeHead(503, { 'Content-Type': 'application/json; charset=utf-8' });
              response.end(JSON.stringify({
                code: 503,
                message: '无法连接后端服务，请确认后端已启动后重试',
                data: null,
              }));
            });
          },
        },
      },
      // 忽略 @ricky0123/vad-web 的 sourcemap 警告
      sourcemapIgnoreList: (relativeSourcePath) => {
        return relativeSourcePath.includes('node_modules/.pnpm/@ricky0123+vad-web');
      },
    },
    optimizeDeps: {
      // No need to optimize vad-web since we load it via script tag
    },
  }
});
