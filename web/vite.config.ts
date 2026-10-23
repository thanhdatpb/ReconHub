import { fileURLToPath } from 'node:url';
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// VITE_API_MODE=mock (default) runs on generated sample data; VITE_API_MODE=http talks to the Spring Boot API.
// Only the chosen implementation is bundled. Relative base so the build can be served from any folder.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiMode = (process.env.VITE_API_MODE ?? env.VITE_API_MODE) === 'http' ? 'http' : 'mock';
  return {
    base: './',
    plugins: [react()],
    resolve: {
      alias: { '@api-impl': fileURLToPath(new URL(`./src/api/${apiMode}.ts`, import.meta.url)) },
    },
    define: { 'import.meta.env.VITE_API_MODE': JSON.stringify(apiMode) },
    server: {
      // npm run dev:http: the API on :8080 serves /api, the dev server proxies it so no CORS is needed
      proxy: { '/api': { target: env.VITE_PROXY_TARGET ?? 'http://localhost:8080', changeOrigin: true } },
    },
    build: { chunkSizeWarningLimit: 2000 },
  };
});
