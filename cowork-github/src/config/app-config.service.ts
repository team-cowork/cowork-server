import {readFileSync} from 'node:fs';
import {Injectable} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {type KafkaConfig, type SASLOptions} from 'kafkajs';
import {
    DEFAULT_GITHUB_INSTALLATION_CACHE_TTL_SECONDS,
    DEFAULT_GITHUB_INSTALLATION_MEMORY_CACHE_MAX_SIZE,
    DEFAULT_GITHUB_ISSUE_MAX_RETRIES,
    DEFAULT_GITHUB_TOKEN_CACHE_TTL_SECONDS,
} from '../github/constants';

@Injectable()
export class AppConfigService {
    constructor(private readonly configService: ConfigService) {}

    get port(): number {
        return this.configService.getOrThrow<number>('PORT');
    }

    get kafkaBrokers(): string[] {
        return this.configService
            .getOrThrow<string>('KAFKA_BOOTSTRAP_SERVERS')
            .split(',')
            .map(broker => broker.trim())
            .filter(Boolean);
    }

    get kafkaGroupId(): string {
        return this.configService.getOrThrow<string>('KAFKA_GROUP_ID');
    }

    get kafkaClientConfig(): KafkaConfig {
        const protocol = (this.configService.get<string>('KAFKA_SECURITY_PROTOCOL') ?? 'PLAINTEXT').toUpperCase();
        if (!['PLAINTEXT', 'SSL', 'SASL_SSL'].includes(protocol)) {
            throw new Error('KAFKA_SECURITY_PROTOCOL must be PLAINTEXT, SSL or SASL_SSL');
        }

        const config: KafkaConfig = {brokers: this.kafkaBrokers};
        if (config.brokers.length === 0) {
            throw new Error('KAFKA_BOOTSTRAP_SERVERS must contain at least one broker');
        }

        if (protocol !== 'PLAINTEXT') {
            const caFile = this.configService.get<string>('KAFKA_SSL_CA_LOCATION');
            config.ssl = caFile === undefined || caFile === ''
                ? true
                : {
                    rejectUnauthorized: true,
                    ca: [readFileSync(caFile, 'utf8')],
                };
        }

        if (protocol === 'SASL_SSL') {
            const mechanism = (this.configService.get<string>('KAFKA_SASL_MECHANISM') ?? 'SCRAM-SHA-512').toLowerCase();
            if (mechanism !== 'plain' && mechanism !== 'scram-sha-256' && mechanism !== 'scram-sha-512') {
                throw new Error('KAFKA_SASL_MECHANISM must be PLAIN, SCRAM-SHA-256 or SCRAM-SHA-512');
            }

            const username = this.configService.getOrThrow<string>('KAFKA_SASL_USERNAME');
            const password = this.configService.getOrThrow<string>('KAFKA_SASL_PASSWORD');
            if (username === '' || password === '') {
                throw new Error('KAFKA_SASL_USERNAME and KAFKA_SASL_PASSWORD must not be empty');
            }

            const sasl = {mechanism, username, password};
            config.sasl = sasl as SASLOptions;
        }

        return config;
    }

    get githubAppId(): string {
        return this.configService.getOrThrow<string>('GITHUB_APP_ID');
    }

    get githubPrivateKey(): string {
        const base64Key =
            this.configService.getOrThrow<string>('GITHUB_PRIVATE_KEY');
        return Buffer.from(base64Key, 'base64').toString('utf8');
    }

    get redisHost(): string {
        return this.configService.getOrThrow<string>('REDIS_HOST');
    }

    get redisPort(): number {
        return this.configService.getOrThrow<number>('REDIS_PORT');
    }

    get githubTokenCacheTtlSeconds(): number {
        return this.getNumber(
            'GITHUB_TOKEN_CACHE_TTL_SECONDS',
            DEFAULT_GITHUB_TOKEN_CACHE_TTL_SECONDS,
        );
    }

    get githubInstallationCacheTtlSeconds(): number {
        return this.getNumber(
            'GITHUB_INSTALLATION_CACHE_TTL_SECONDS',
            DEFAULT_GITHUB_INSTALLATION_CACHE_TTL_SECONDS,
        );
    }

    get githubIssueMaxRetries(): number {
        return this.getNumber(
            'GITHUB_ISSUE_MAX_RETRIES',
            DEFAULT_GITHUB_ISSUE_MAX_RETRIES,
        );
    }

    get githubInstallationMemoryCacheMaxSize(): number {
        return this.getNumber(
            'GITHUB_INSTALLATION_MEMORY_CACHE_MAX_SIZE',
            DEFAULT_GITHUB_INSTALLATION_MEMORY_CACHE_MAX_SIZE,
        );
    }

    get internalApiKey(): string {
        return this.configService.getOrThrow<string>('INTERNAL_API_KEY');
    }

    get githubWebhookSecret(): string {
        return this.configService.getOrThrow<string>('GITHUB_WEBHOOK_SECRET');
    }

    private getNumber(key: string, fallback: number): number {
        return Number(this.configService.get(key) ?? fallback);
    }
}
