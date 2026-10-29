const config = {
	preset: 'ts-jest',
	testEnvironment: 'node',
	rootDir: 'src',
	testRegex: String.raw`.*\.spec\.ts$`,
	moduleFileExtensions: ['js', 'json', 'ts'],
	// 소스는 Node ESM 규칙에 따라 `.js` 확장자로 import하므로 테스트에서는 같은 이름의 `.ts` 파일로 연결한다.
	moduleNameMapper: {[String.raw`^(\.{1,2}/.*)\.js$`]: '$1'},
	collectCoverageFrom: ['**/*.(t|j)s'],
	coverageDirectory: '../coverage',
};

export default config;
