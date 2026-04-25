import { HttpService } from '@nestjs/axios';
import { Inject, Injectable, Logger } from '@nestjs/common';
import { AxiosError } from 'axios';
import { sign } from 'jsonwebtoken';
import type Redis from 'ioredis';
import { firstValueFrom } from 'rxjs';
import { AppConfigService } from '../../config/app-config.service';
import { GITHUB_API, GITHUB_HEADERS, REDIS_CLIENT } from '../constants';
import { githubCacheKeys } from '../github.cache';
import { GithubClientError } from '../github.errors';

@Injectable()
export class GithubAuthService {
  private readonly logger = new Logger(GithubAuthService.name);
  private readonly installationIdCache = new Map<string, number>();

  constructor(
    private readonly httpService: HttpService,
    private readonly config: AppConfigService,
    @Inject(REDIS_CLIENT) private readonly redis: Redis,
  ) {}

  async getInstallationToken(owner: string): Promise<string> {
    const installationId = await this.resolveInstallationId(owner);
    const cacheKey = githubCacheKeys.token(installationId);

    const cached = await this.redis.get(cacheKey);
    if (cached) return cached;

    const jwt = this.generateJwt();
    const { data } = await this.requestGithub<{ token: string }>(() =>
      firstValueFrom(
        this.httpService.post<{ token: string }>(
          `${GITHUB_API}/app/installations/${installationId}/access_tokens`,
          {},
          { headers: { ...GITHUB_HEADERS, Authorization: `Bearer ${jwt}` } },
        ),
      ),
    );

    await this.redis.set(
      cacheKey,
      data.token,
      'EX',
      this.config.githubTokenCacheTtlSeconds,
    );
    this.logger.log(
      `Installation token issued for installationId=${installationId}`,
    );
    return data.token;
  }

  private async resolveInstallationId(owner: string): Promise<number> {
    const cachedInMemory = this.getCachedInstallationId(owner);
    if (cachedInMemory) return cachedInMemory;

    const installationCacheKey = githubCacheKeys.installation(owner);
    const cachedInRedis = await this.redis.get(installationCacheKey);
    if (cachedInRedis) {
      const installationId = Number(cachedInRedis);
      this.cacheInstallationId(owner, installationId);
      return installationId;
    }

    const jwt = this.generateJwt();
    const { data } = await this.requestGithub<{ id: number }>(() =>
      firstValueFrom(
        this.httpService.get<{ id: number }>(
          `${GITHUB_API}/orgs/${owner}/installation`,
          {
            headers: { ...GITHUB_HEADERS, Authorization: `Bearer ${jwt}` },
          },
        ),
      ),
    );
    const installationId = data.id;

    this.cacheInstallationId(owner, installationId);
    await this.redis.set(
      installationCacheKey,
      installationId.toString(),
      'EX',
      this.config.githubInstallationCacheTtlSeconds,
    );

    return installationId;
  }

  private getCachedInstallationId(owner: string): number | undefined {
    const installationId = this.installationIdCache.get(owner);
    if (installationId === undefined) return undefined;

    this.installationIdCache.delete(owner);
    this.installationIdCache.set(owner, installationId);
    return installationId;
  }

  private cacheInstallationId(owner: string, installationId: number): void {
    if (this.installationIdCache.has(owner)) {
      this.installationIdCache.delete(owner);
    }

    this.installationIdCache.set(owner, installationId);

    if (
      this.installationIdCache.size >
      this.config.githubInstallationMemoryCacheMaxSize
    ) {
      const oldestOwner = this.installationIdCache.keys().next().value as
        | string
        | undefined;
      if (oldestOwner !== undefined) {
        this.installationIdCache.delete(oldestOwner);
      }
    }
  }

  private generateJwt(): string {
    const now = Math.floor(Date.now() / 1000);

    return sign(
      { iat: now - 60, exp: now + 540, iss: this.config.githubAppId },
      this.config.githubPrivateKey,
      { algorithm: 'RS256' },
    );
  }

  private async requestGithub<T>(
    request: () => Promise<{ data: T }>,
  ): Promise<{ data: T }> {
    try {
      return await request();
    } catch (error) {
      if (
        error instanceof AxiosError &&
        error.response &&
        error.response.status < 500
      ) {
        throw new GithubClientError(
          (error.response.data as { message?: string })?.message ??
            error.message,
          error.response.status,
        );
      }

      throw error;
    }
  }
}
