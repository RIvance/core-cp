import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import manifest from './package.json' with { type: 'json' };

const externalPackages = Object.keys({ ...manifest.dependencies, ...manifest.peerDependencies }).filter(
  (name) => name !== 'monaco-editor',
);

export default defineConfig({
  plugins: [react()],
  base: './',
  worker: { format: 'es' },
  // The installed-package consumer is a separate project; only these pages belong to this dev server.
  optimizeDeps: { entries: ['tests/browser/**/*.html'] },
  build: {
    target: 'es2022',
    lib: {
      entry: {
        index: 'src/index.ts',
        api: 'src/api.ts',
        core: 'src/core/index.ts',
        embedded: 'src/embedded/index.ts',
        editor: 'src/editor/index.ts',
        transport: 'src/transport/index.ts',
        lsp: 'src/adapters/lsp/service.ts',
        examples: 'src/workspace/examples.ts',
        themes: 'src/appearance/index.ts',
        workspace: 'src/workspace/index.ts',
        styles: 'styles.js',
      },
      formats: ['es'],
      fileName: (_format, entry) => `${entry}.js`,
      cssFileName: 'styles',
    },
    rolldownOptions: {
      external: (id) => externalPackages.some((name) => id === name || id.startsWith(`${name}/`)),
    },
  },
});
