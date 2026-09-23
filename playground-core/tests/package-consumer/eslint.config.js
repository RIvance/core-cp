import eslint from '@eslint/js';
import typescript from 'typescript-eslint';
import globals from 'globals';

export default typescript.config(
  { ignores: ['dist/**', 'node_modules/**', 'test-results/**'] },
  eslint.configs.recommended,
  ...typescript.configs.recommendedTypeChecked.map((config) => ({ ...config, files: ['*.ts'] })),
  {
    files: ['*.ts'],
    languageOptions: { parserOptions: { projectService: true }, globals: { ...globals.browser, ...globals.node } },
    rules: { '@typescript-eslint/consistent-type-imports': 'error' },
  },
);
