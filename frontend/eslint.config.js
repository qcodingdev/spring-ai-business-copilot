import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

export default [
  { ignores: ['dist/**', 'coverage/**', 'playwright-report/**', 'test-results/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.{ts,vue}'],
    languageOptions: {
      globals: {
        document: 'readonly',
        window: 'readonly',
        location: 'readonly',
        history: 'readonly',
        localStorage: 'readonly',
        URLSearchParams: 'readonly',
        HTMLDialogElement: 'readonly',
        HTMLElement: 'readonly',
        HTMLInputElement: 'readonly',
        Event: 'readonly',
        CustomEvent: 'readonly',
        File: 'readonly',
        FormData: 'readonly',
        setTimeout: 'readonly',
        clearTimeout: 'readonly',
        structuredClone: 'readonly',
      },
      parserOptions: {
        parser: tseslint.parser,
        extraFileExtensions: ['.vue'],
      },
    },
    rules: {
      'vue/multi-word-component-names': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/multiline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
    },
  },
]
