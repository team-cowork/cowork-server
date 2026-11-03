/**
배포 환경이 주입하는 환경변수 이름이다.
*/
export const envName = {
	githubAppId: 'GITHUB_APP_ID',
	githubInstallationCacheTtlSeconds: 'GITHUB_INSTALLATION_CACHE_TTL_SECONDS',
	githubIssueMaxRetries: 'GITHUB_ISSUE_MAX_RETRIES',
	githubPrivateKey: 'GITHUB_PRIVATE_KEY',
	githubTokenCacheTtlSeconds: 'GITHUB_TOKEN_CACHE_TTL_SECONDS',
	githubWebhookSecret: 'GITHUB_WEBHOOK_SECRET',
	internalApiKey: 'INTERNAL_API_KEY',
	kafkaBootstrapServers: 'KAFKA_BOOTSTRAP_SERVERS',
	kafkaGroupId: 'KAFKA_GROUP_ID',
	kafkaSecurityProtocol: 'KAFKA_SECURITY_PROTOCOL',
	port: 'PORT',
	redisHost: 'REDIS_HOST',
	redisPort: 'REDIS_PORT',
} as const;
