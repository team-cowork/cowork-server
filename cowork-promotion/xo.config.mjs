import globals from 'globals';

const config = [
    { ignores: ['public/**', 'dist/**', 'coverage/**', '**/*.{html,css,md,json,yml,yaml}'] },
    {
        space: 4,
        rules: {
            '@stylistic/object-curly-spacing': ['error', 'always'],
            '@stylistic/brace-style': ['error', '1tbs', { allowSingleLine: false }],
            '@stylistic/curly-newline': 'off',
            // Existing data and DOM contracts do not require boolean prefixes.
            'unicorn/consistent-boolean-name': 'off',
            // Keep readable composition and evaluation order without mandatory rewrites.
            'unicorn/max-nested-calls': 'off',
            'unicorn/no-declarations-before-early-exit': 'off',
            // These maps test the selected value, not ownership of a dynamic property.
            'unicorn/no-computed-property-existence-check': 'off',
            'n/prefer-global/process': ['error', 'always'],
            complexity: 'off',
            'unicorn/import-style': ['error', { styles: { path: { named: true } } }],
            // Keep positional captures and portable Unicode regex syntax.
            'regexp/prefer-named-capture-group': 'off',
            'require-unicode-regexp': ['error', { requireFlag: 'u' }],
            // Long HTML template strings are intentional.
            '@stylistic/max-len': ['error', { code: 200, ignoreTemplateLiterals: true, ignoreStrings: true }],
        },
    },
    {
        files: ['src/**/*.js', 'src/**/*.mjs'],
        languageOptions: { globals: globals.browser },
        rules: {
            // Browser assets must not assume the newest DOM/Iterator APIs.
            'unicorn/prefer-dom-node-html-methods': 'off',
            'unicorn/prefer-top-level-await': 'off',
            // ID lookup and DocumentFragment queries retain their existing semantics.
            'unicorn/prefer-query-selector': 'off',
            'unicorn/prefer-scoped-selector': 'off',
            'unicorn/prefer-iterator-to-array': 'off',
        },
    },
    {
        files: ['scripts/serve.mjs'],
        // The dev server retains FSWatcher handles for close()/event subscriptions.
        rules: { 'n/prefer-promises/fs': 'off' },
    },
    {
        files: ['scripts/**/*.mjs', 'test/**/*.mjs', 'src/js/core/app.js'],
        // The build traverses dependencies and writes output in order.
        rules: { 'no-await-in-loop': 'off' },
    },
];

export default config;
