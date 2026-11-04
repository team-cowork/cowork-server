// XO 기본 규칙은 그대로 사용하고, upstream 버그 우회 설정만 둔다.
const config = [
	{
		files: ['src/**/*.ts'],
		settings: {
			// `eslint-config-xo` populates `import-x/extensions` without a leading dot, but
			// `path.extname()` always returns one, so `hasValidExtension()` never matches and
			// every `ExportMap`-based rule (e.g. `import-x/no-cycle`) silently sees no module.
			// @todo upstream `eslint-config-xo`가 점 포함 확장자를 채우면 이 파일을 삭제한다.
			'import-x/extensions': ['.ts', '.tsx', '.mts', '.cts'],
		},
	},
];

export default config;
