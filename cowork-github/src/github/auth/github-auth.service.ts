import { HttpService } from '@nestjs/axios';
import { Inject, Injectable, Logger } from '@nestjs/common';
import { sign } from 'jsonwebtoken';
import type Redis from 'ioredis';
import { firstValueFrom } from 'rxjs';
import { AppConfigService } from '../../config/app-config.service';
import { GITHUB_API, GITHUB_HEADERS, REDIS_CLIENT } from '../constants';
import { githubCacheKeys } from '../github.cache';

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
    const { data } = await firstValueFrom(
      this.httpService.post<{ token: string }>(
        `${GITHUB_API}/app/installations/${installationId}/access_tokens`,
        {},
        { headers: { ...GITHUB_HEADERS, Authorization: `Bearer ${jwt}` } },
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
    const cachedInMemory = this.installationIdCache.get(owner);
    if (cachedInMemory) return cachedInMemory;

    const installationCacheKey = githubCacheKeys.installation(owner);
    const cachedInRedis = await this.redis.get(installationCacheKey);
    if (cachedInRedis) {
      const installationId = Number(cachedInRedis);
      this.installationIdCache.set(owner, installationId);
      return installationId;
    }

    const jwt = this.generateJwt();
    const { data } = await firstValueFrom(
      this.httpService.get<{ id: number }>(
        `${GITHUB_API}/orgs/${owner}/installation`,
        {
          headers: { ...GITHUB_HEADERS, Authorization: `Bearer ${jwt}` },
        },
      ),
    );
    const installationId = data.id;

    this.installationIdCache.set(owner, installationId);
    await this.redis.set(
      installationCacheKey,
      installationId.toString(),
      'EX',
      this.config.githubInstallationCacheTtlSeconds,
    );

    return installationId;
  }

  private generateJwt(): string {
    const now = Math.floor(Date.now() / 1000);

    return sign(
      { iat: now - 60, exp: now + 600, iss: this.config.githubAppId },
      this.config.githubPrivateKey,
      { algorithm: 'RS256' },
    );
  }
}
