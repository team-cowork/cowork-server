import globals from 'globals';

const config = [
    {ignores: ['dist/**', 'coverage/**']},
    {
        space: 4,
        rules: {
            // GitHub API and Kafka payload field names and null values are external contracts.
            '@typescript-eslint/no-restricted-types': 'off',
            '@typescript-eslint/naming-convention': 'off',
            // NestJS decorators (@Injectable(), @Controller() 등)는 capitalized 함수 호출이지만 new로 호출하지 않는다.
            'new-cap': ['error', {capIsNew: false}],
            // Nullable checks cover both GitHub API nulls and absent fields.
            eqeqeq: ['error', 'always', {null: 'ignore'}],
            'no-eq-null': 'off',
            // Node globals and class organization follow the existing service conventions.
            'n/prefer-global/process': ['error', 'always'],
            'n/prefer-global/buffer': ['error', 'always'],
            'unicorn/consistent-class-member-order': 'off',
        },
        languageOptions: {globals: globals.node},
    },
    {
        files: ['src/**/*.ts', 'test/**/*.ts'],
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
        files: [
            'src/github/github.controller.ts',
            'src/github/pull-request/pull-request.controller.ts',
            'src/github/issue-write/issue-write-command.controller.ts',
        ],
        // Unrecoverable Kafka failures exit so the container restarts and retries the uncommitted message.
        rules: {'unicorn/no-process-exit': 'off'},
    },
    {
        files: ['src/**/*.spec.ts', 'test/**/*.ts'],
        languageOptions: {globals: globals.jest},
    },
];

export default config;
