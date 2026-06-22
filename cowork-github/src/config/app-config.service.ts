import { Injectable } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
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
      .getOrThrow<string>('KAFKA_BROKERS')
      .split(',')
      .map((broker) => broker.trim())
      .filter(Boolean);
  }

  get kafkaGroupId(): string {
    return this.configService.getOrThrow<string>('KAFKA_GROUP_ID');
  }

  get githubAppId(): string {
    return this.configService.getOrThrow<string>('GITHUB_APP_ID');
  }

  get githubPrivateKey(): string {
    const base64Key =
      this.configService.getOrThrow<string>('GITHUB_PRIVATE_KEY');
    return Buffer.from(base64Key, 'base64').toString('utf-8');
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

  private getNumber(key: string, fallback: number): number {
    return Number(this.configService.get(key) ?? fallback);
  }
}
