export const TEST_ENV: Record<string, string> = {
  PORT: '3000',
  KAFKA_BROKERS: 'localhost:9092',
  KAFKA_GROUP_ID: 'cowork-github-test',
  GITHUB_APP_ID: '123456',
  GITHUB_PRIVATE_KEY: Buffer.from('test-private-key').toString('base64'),
  REDIS_HOST: 'localhost',
  REDIS_PORT: '6379',
};

export function applyTestEnv(): NodeJS.ProcessEnv {
  const originalEnv = { ...process.env };
  process.env = {
    ...process.env,
    ...TEST_ENV,
  };
  return originalEnv;
}

export function restoreTestEnv(originalEnv: NodeJS.ProcessEnv): void {
  process.env = originalEnv;
}
