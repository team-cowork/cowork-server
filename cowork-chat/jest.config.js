module.exports = {
    preset: 'ts-jest',
    testEnvironment: 'node',
    rootDir: 'src',
    testRegex: String.raw`.*\.spec\.ts$`,
    moduleFileExtensions: ['js', 'json', 'ts'],
    collectCoverageFrom: ['**/*.(t|j)s'],
    coverageDirectory: '../coverage',
};
