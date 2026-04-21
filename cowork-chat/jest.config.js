module.exports = {
    preset: 'ts-jest',
    testEnvironment: 'node',
    rootDir: 'src',
    testRegex: '.*\\.spec\\.ts$',
    moduleFileExtensions: ['js', 'json', 'ts'],
    collectCoverageFrom: ['**/*.(t|j)s'],
    coverageDirectory: '../coverage',
};
