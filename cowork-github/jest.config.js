const config = {
	testEnvironment: 'node',
	rootDir: 'src',
	testRegex: String.raw`.*\.spec\.ts$`,
	moduleFileExtensions: ['js', 'json', 'ts'],
	// 테스트는 `jest.mock` 호이스팅을 위해 CommonJS로 변환해 실행한다. `isolatedModules`로 변환하므로 타입 검사는 하지 않는다.
	transform: {[String.raw`^.+\.ts$`]: ['ts-jest', {tsconfig: {module: 'commonjs'}}]},
	// 소스는 Node ESM 규칙에 따라 `.js` 확장자로 import하므로 테스트에서는 같은 이름의 `.ts` 파일로 연결한다.
	moduleNameMapper: {[String.raw`^(\.{1,2}/.*)\.js$`]: '$1'},
	collectCoverageFrom: ['**/*.(t|j)s'],
	coverageDirectory: '../coverage',
};

export default config;
