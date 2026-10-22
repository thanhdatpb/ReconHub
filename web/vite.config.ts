import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Relative base so the build can be served from any folder (static hosting, preview artifact).
export default defineConfig({
  base: './',
  plugins: [react()],
  build: { chunkSizeWarningLimit: 2000 },
});
