import globals from 'globals';

const config = [
    { ignores: ['dist/**', 'coverage/**', '**/*.{html,css,md,json,yml,yaml}'] },
    {
        space: 4,
        rules: {
            // Keep the repository's spacing and JSDoc conventions.
            '@stylistic/object-curly-spacing': ['error', 'always'],
            '@stylistic/brace-style': ['error', '1tbs', { allowSingleLine: false }],
            '@stylistic/curly-newline': 'off',
            'jsdoc/require-asterisk-prefix': ['error', 'always'],
            // Document non-obvious contracts; typed parameters need no generated tags.
            'jsdoc/require-param': 'off',
            'jsdoc/check-indentation': 'off',
            '@stylistic/max-len': ['error', { code: 200, ignoreComments: true, ignoreTemplateLiterals: true }],
            'import-x/no-unassigned-import': ['error', { allow: ['reflect-metadata', 'dotenv/config'] }],
            'unicorn/single-line-block-comment-style': 'off',
            // API/database names and null values are part of existing contracts.
            '@typescript-eslint/no-restricted-types': 'off',
            '@typescript-eslint/naming-convention': 'off',
            'unicorn/consistent-boolean-name': 'off',
            // Keep readable composition and evaluation order without mandatory rewrites.
            'unicorn/max-nested-calls': 'off',
            'unicorn/no-declarations-before-early-exit': 'off',
            // These maps test the selected value, not ownership of a dynamic property.
            'unicorn/no-computed-property-existence-check': 'off',
            // Constructor injection and ordered I/O are intentional in NestJS.
            'max-params': 'off',
            'no-await-in-loop': 'off',
            'new-cap': ['error', { capIsNew: false }],
            'no-console': 'error',
            '@typescript-eslint/no-explicit-any': 'error',
            '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', ignoreRestSiblings: true }],
            // Nullable checks cover both MongoDB nulls and absent fields.
            eqeqeq: ['error', 'always', { null: 'ignore' }],
            'no-eq-null': 'off',
            'no-void': ['error', { allowAsStatement: true }],
            // Async cleanup must be awaited before CLI termination.
            'unicorn/no-async-promise-finally': 'off',
            // Dependency factories and object construction may initialize in several steps.
            'unicorn/no-immediate-mutation': 'off',
            // The compiler targets ES2024, which has no Iterator helper declarations.
            'unicorn/prefer-iterator-to-array': 'off',
            '@typescript-eslint/consistent-type-assertions': ['error', { assertionStyle: 'as', objectLiteralTypeAssertions: 'allow' }],
            '@typescript-eslint/no-extraneous-class': ['error', { allowStaticOnly: true, allowWithDecorator: true }],
            // Preserve short-circuit evaluation order and explicit nested control flow.
            'unicorn/prefer-simple-condition-first': 'off',
            'unicorn/no-break-in-nested-loop': 'off',
            '@typescript-eslint/switch-exhaustiveness-check': ['error', { allowDefaultCaseForExhaustiveSwitch: true }],

            // Node globals and class organization follow the existing service conventions.
            'n/prefer-global/process': ['error', 'always'],
            'n/prefer-global/buffer': ['error', 'always'],
            'unicorn/consistent-class-member-order': 'off',
            // Keep orchestration size separate from correctness linting.
            complexity: 'off',
            'max-lines': 'off',
            // Truthiness intentionally treats absent/empty config values alike.
            '@typescript-eslint/strict-boolean-expressions': ['error', {
                allowNullableString: true,
                allowNullableNumber: true,
                allowNullableBoolean: true,
            }],
            // Existing capture-group indexes and numeric parsing are intentional.
            'regexp/prefer-named-capture-group': 'off',
            'require-unicode-regexp': ['error', { requireFlag: 'u' }],

        },
        languageOptions: { globals: globals.node },
    },
    {
        files: ['src/**/*.ts'],
        rules: {
            // TypeScript is compiled to CommonJS; runtime imports stay extensionless.
            'n/file-extension-in-import': 'off',
            'unicorn/prefer-module': 'off',
            'unicorn/prefer-top-level-await': 'off',
        },
        languageOptions: {
            parserOptions: {
                projectService: true,
                tsconfigRootDir: import.meta.dirname,
            },
        },
    },
    {
        files: ['src/**/*.controller.ts', 'src/common/adapter/redis-io.adapter.ts'],
        // Forward the original Promise: async wrappers change decorator metadata and rejection ownership.
        rules: { '@typescript-eslint/promise-function-async': 'off' },
    },
    {
        files: ['src/**/*.schema.ts', 'src/main.ts', 'src/ops/*.cli.ts'],
        // Schema indexes, startup and CLI dispatch run at module initialization.
        rules: { 'unicorn/no-top-level-side-effects': 'off' },
    },
    {
        files: ['src/**/*consumer.ts', 'src/main.ts', 'src/ops/*.cli.ts'],
        // Fatal startup failures must terminate the process instead of serving partial state.
        rules: { 'unicorn/no-process-exit': 'off' },
    },
    {
        files: ['src/chat/util/emoji.ts'],
        // RGI_Emoji is a Unicode strings property and specifically requires /v.
        rules: { 'require-unicode-regexp': ['error', { requireFlag: 'v' }] },
    },
    {
        files: ['src/ops/*.cli.ts'],
        rules: { 'no-console': 'off', '@typescript-eslint/strict-void-return': 'off' },
    },
    {
        files: ['jest.config.js'],
        rules: { 'unicorn/prefer-module': 'off' },
    },
    {
        files: ['src/**/*.spec.ts'],
        rules: {
            '@typescript-eslint/no-empty-function': ['error', { allow: ['arrowFunctions'] }],
            // Each mock invocation gets fresh default header objects.
            'unicorn/no-object-as-default-parameter': 'off',
        },
        languageOptions: { globals: globals.jest },
    },
];

export default config;
