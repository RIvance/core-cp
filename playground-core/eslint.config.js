import eslint from '@eslint/js';
import typescript from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';

export default typescript.config(
  {
    ignores: [
      '**/dist/**',
      '**/node_modules/**',
      '**/generated/**',
      '**/target/**',
      'scalajs-api/**',
      'test-results/**',
      'playwright-report/**',
      // This is a separate npm project; test:package installs and lints it against the packed library.
      'tests/package-consumer/**',
    ],
  },
  eslint.configs.recommended,
  ...typescript.configs.recommendedTypeChecked.map((config) => ({
    ...config,
    files: ['src/**/*.ts', 'src/**/*.tsx', 'tests/**/*.ts', '*.ts'],
  })),
  {
    files: ['src/**/*.ts', 'src/**/*.tsx', 'tests/**/*.ts', '*.ts'],
    languageOptions: { parserOptions: { projectService: true }, globals: { ...globals.browser, ...globals.node } },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error',
      '@typescript-eslint/consistent-type-imports': 'error',
    },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['error', { patterns: ['**/apps/**', '**/scalajs-api/**', '**/generated/**'] }],
      'no-restricted-syntax': [
        'error',
        {
          selector: 'ImportExpression[source.value=/apps|scalajs-api|generated/]',
          message: 'The IDE package cannot load application code or optional compiler APIs.',
        },
      ],
    },
  },
  {
    files: ['src/{core,editor,workspace,workbench,persistence,transport}/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['**/adapters/**', '**/generated/**', '**/scalajs-api/**', '**/apps/**', 'vscode-languageserver*'],
              message:
                'Shared playground components depend on neutral contracts. Inject concrete languages and transports at the composition root.',
            },
          ],
        },
      ],
      'no-restricted-syntax': [
        'error',
        {
          selector: 'ImportExpression[source.value=/adapters|generated|scalajs-api|apps|vscode-languageserver/]',
          message: 'Load concrete language adapters only at the composition root.',
        },
      ],
    },
  },
  {
    files: ['src/{core,editor,components,appearance,embedded}/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '**/workbench/**',
                '**/persistence/**',
                '**/adapters/**',
                '**/generated/**',
                '**/scalajs-api/**',
                '**/apps/**',
                'vscode-languageserver*',
              ],
              message:
                'Shared components and embedded editors cannot depend on full-application policy or concrete language adapters.',
            },
          ],
        },
      ],
      'no-restricted-syntax': [
        'error',
        {
          selector:
            'ImportExpression[source.value=/workbench|persistence|adapters|generated|scalajs-api|apps|vscode-languageserver/]',
          message:
            'Shared components and embedded editors cannot load full-application or concrete language implementations.',
        },
      ],
    },
  },
  { files: ['*.js', 'scripts/*.mjs'], languageOptions: { globals: globals.node } },
);
