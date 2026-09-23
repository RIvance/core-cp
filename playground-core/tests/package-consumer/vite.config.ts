import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: '/embedded/',
  worker: { format: 'es' },
  build: { rolldownOptions: { input: ['index.html', 'editors.html'] }, target: 'es2022', chunkSizeWarningLimit: 4000 },
});
