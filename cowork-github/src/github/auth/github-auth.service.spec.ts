import { Test, TestingModule } from '@nestjs/testing';
import { HttpService } from '@nestjs/axios';
import { of } from 'rxjs';
import { GithubAuthService } from './github-auth.service';
import { AppConfigService } from '../../config/app-config.service';
import { REDIS_CLIENT } from '../constants';
import { githubCacheKeys } from '../github.cache';

jest.mock('jsonwebtoken', () => ({
  sign: jest.fn().mockReturnValue('mock-jwt'),
}));

describe('GithubAuthService', () => {
  let service: GithubAuthService;
  let httpService: { get: jest.Mock; post: jest.Mock };
  let redis: { get: jest.Mock; set: jest.Mock };

  const mockConfig = {
    githubAppId: 'app-123',
    githubPrivateKey: 'private-key',
    githubTokenCacheTtlSeconds: 3300,
    githubInstallationCacheTtlSeconds: 86400,
  };

  beforeEach(async () => {
    httpService = { get: jest.fn(), post: jest.fn() };
    redis = { get: jest.fn(), set: jest.fn().mockResolvedValue('OK') };

    const module: TestingModule = await Test.createTestingModule({
      providers: [
        GithubAuthService,
        { provide: HttpService, useValue: httpService },
        { provide: AppConfigService, useValue: mockConfig },
        { provide: REDIS_CLIENT, useValue: redis },
      ],
    }).compile();

    service = module.get<GithubAuthService>(GithubAuthService);
  });

  afterEach(() => jest.clearAllMocks());

  it('installationId 메모리 캐시 히트 시 GitHub API를 호출하지 않는다', async () => {
    (
      service as unknown as { installationIdCache: Map<string, number> }
    ).installationIdCache.set('my-org', 111);
    redis.get.mockResolvedValueOnce('cached-token');

    const token = await service.getInstallationToken('my-org');

    expect(token).toBe('cached-token');
    expect(httpService.get).not.toHaveBeenCalled();
    expect(redis.get).toHaveBeenCalledWith(githubCacheKeys.token(111));
  });

  it('installationId Redis 캐시 히트 시 GitHub API를 호출하지 않고 메모리 캐시를 채운다', async () => {
    redis.get
      .mockResolvedValueOnce('999') // installation cache hit
      .mockResolvedValueOnce('redis-token'); // token cache hit

    const token = await service.getInstallationToken('my-org');

    expect(token).toBe('redis-token');
    expect(httpService.get).not.toHaveBeenCalled();
    expect(
      (
        service as unknown as { installationIdCache: Map<string, number> }
      ).installationIdCache.get('my-org'),
    ).toBe(999);
  });

  it('캐시 미스 시 GitHub API를 호출해 토큰을 발급하고 Redis에 저장한다', async () => {
    redis.get.mockResolvedValue(null);
    httpService.get.mockReturnValue(of({ data: { id: 555 } }));
    httpService.post.mockReturnValue(of({ data: { token: 'new-token' } }));

    const token = await service.getInstallationToken('my-org');

    expect(token).toBe('new-token');
    expect(httpService.get).toHaveBeenCalledTimes(1);
    expect(httpService.post).toHaveBeenCalledTimes(1);
    expect(redis.set).toHaveBeenCalledWith(
      githubCacheKeys.installation('my-org'),
      '555',
      'EX',
      86400,
    );
    expect(redis.set).toHaveBeenCalledWith(
      githubCacheKeys.token(555),
      'new-token',
      'EX',
      3300,
    );
  });

  it('연속 요청 시 메모리 캐시로 API 재호출을 방지한다', async () => {
    redis.get.mockResolvedValue(null);
    httpService.get.mockReturnValue(of({ data: { id: 777 } }));
    httpService.post.mockReturnValue(of({ data: { token: 'token-a' } }));

    await service.getInstallationToken('my-org');

    redis.get.mockResolvedValue('token-a'); // token still cached
    await service.getInstallationToken('my-org');

    expect(httpService.get).toHaveBeenCalledTimes(1); // installationId API: 1번만
  });
});
