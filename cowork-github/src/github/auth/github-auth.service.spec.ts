import {Test, type TestingModule} from '@nestjs/testing';
import {HttpService} from '@nestjs/axios';
import {AxiosError} from 'axios';
import {of, throwError} from 'rxjs';
import {AppConfigService} from '../../config/app-config.service';
import {REDIS_CLIENT} from '../constants';
import {githubCacheKeys} from '../github.cache';
import {GithubClientError} from '../github.errors';
import {GithubAuthService} from './github-auth.service';

jest.mock('jsonwebtoken', () => ({
    sign: jest.fn().mockReturnValue('mock-jwt'),
}));

describe('GithubAuthService', () => {
    let service: GithubAuthService;
    let httpService: {get: jest.Mock; post: jest.Mock};
    let redis: {get: jest.Mock; set: jest.Mock; del: jest.Mock; eval: jest.Mock};

    const mockConfig = {
        githubAppId: 'app-123',
        githubPrivateKey: 'private-key',
        githubTokenCacheTtlSeconds: 3300,
        githubInstallationCacheTtlSeconds: 86_400,
    };

    beforeEach(async () => {
        httpService = {get: jest.fn(), post: jest.fn()};
        redis = {
            get: jest.fn(), set: jest.fn().mockResolvedValue('OK'), del: jest.fn(), eval: jest.fn(),
        };

        const module: TestingModule = await Test.createTestingModule({
            providers: [
                GithubAuthService,
                {provide: HttpService, useValue: httpService},
                {provide: AppConfigService, useValue: mockConfig},
                {provide: REDIS_CLIENT, useValue: redis},
            ],
        }).compile();

        service = module.get<GithubAuthService>(GithubAuthService);
    });

    afterEach(() => jest.clearAllMocks());

    const createAxiosError = (status: number, message: string): AxiosError =>
        new AxiosError(message, undefined, {headers: {}}, undefined, {
            status,
            statusText: 'error',
            headers: {},
            config: {headers: {}},
            data: {message},
        });

    it('installationId Redis 캐시 히트 시 GitHub API를 호출하지 않는다', async () => {
        redis.get
            .mockResolvedValueOnce('999') // Installation cache hit
            .mockResolvedValueOnce('redis-token'); // Token cache hit

        const token = await service.getInstallationToken('my-org');

        expect(token).toBe('redis-token');
        expect(httpService.get).not.toHaveBeenCalled();
        expect(redis.get).toHaveBeenCalledWith(githubCacheKeys.token(999));
    });

    it('캐시 미스 시 GitHub API를 호출해 토큰을 발급하고 Redis에 저장한다', async () => {
        redis.get.mockResolvedValue(null);
        httpService.get.mockReturnValue(of({data: {id: 555}}));
        httpService.post.mockReturnValue(of({data: {token: 'new-token'}}));

        const token = await service.getInstallationToken('my-org');

        expect(token).toBe('new-token');
        expect(httpService.get).toHaveBeenCalledTimes(1);
        expect(httpService.post).toHaveBeenCalledTimes(1);
        expect(redis.set).toHaveBeenCalledWith(
            githubCacheKeys.installation('my-org'),
            '555',
            'EX',
            86_400,
            'NX',
        );
        expect(redis.set).toHaveBeenCalledWith(
            githubCacheKeys.token(555),
            'new-token',
            'EX',
            3300,
        );
    });

    describe('설치 변경', () => {
        let store: Map<string, string>;

        // 설치 매핑의 덮어쓰기·조건부 삭제 결과를 확인하려고 필요한 명령만 흉내 낸다.
        const useFakeRedis = (): void => {
            store = new Map();
            redis.get.mockImplementation(async (key: string) => store.get(key) ?? null);
            redis.set.mockImplementation(async (key: string, value: string, ...options: string[]) => {
                if (options.includes('NX') && store.has(key)) {
                    return null;
                }

                store.set(key, value);
                return 'OK';
            });
            redis.del.mockImplementation(async (key: string) => Number(store.delete(key)));
            redis.eval.mockImplementation(async (_script: string, _keyCount: number, key: string, value: string) =>
                store.get(key) === value ? Number(store.delete(key)) : 0);
        };

        const installationIdUsed = async (owner: string): Promise<number> => {
            httpService.post.mockClear();
            await service.getInstallationToken(owner);
            const [[url]] = httpService.post.mock.calls as Array<[string]>;
            return Number(/installations\/(?<id>\d+)\//v.exec(url)?.groups?.id);
        };

        beforeEach(() => {
            useFakeRedis();
            httpService.post.mockReturnValue(of({data: {token: 'token'}}));
        });

        it('재설치를 반영하면 이전 설치 ID와 토큰을 쓰지 않는다', async () => {
            await service.cacheInstallation('my-org', 1);
            await service.getInstallationToken('my-org');

            await service.evictInstallation('my-org', 1);
            await service.cacheInstallation('my-org', 2);

            expect(store.has(githubCacheKeys.token(1))).toBe(false);
            expect(await installationIdUsed('my-org')).toBe(2);
        });

        it('이전 설치의 삭제가 늦게 도착해도 새 설치 매핑을 지우지 않는다', async () => {
            await service.cacheInstallation('my-org', 2);

            await service.evictInstallation('my-org', 1);

            expect(store.get(githubCacheKeys.installation('my-org'))).toBe('2');
        });

        it('중복 설치 이벤트는 같은 매핑을 유지한다', async () => {
            await service.cacheInstallation('my-org', 2);
            await service.cacheInstallation('my-org', 2);

            expect(await installationIdUsed('my-org')).toBe(2);
        });

        it('조회 중에 기록된 새 설치 매핑을 이전 조회 결과로 덮지 않는다', async () => {
            httpService.get.mockImplementation(() => {
                store.set(githubCacheKeys.installation('my-org'), '2'); // 조회 중에 설치 이벤트가 먼저 반영됨
                return of({data: {id: 1}});
            });

            await service.getInstallationToken('my-org');

            expect(store.get(githubCacheKeys.installation('my-org'))).toBe('2');
        });

        it('설치 이벤트의 owner 대소문자가 달라도 같은 매핑을 갱신한다', async () => {
            await service.cacheInstallation('My-Org', 2);

            expect(await installationIdUsed('my-org')).toBe(2);
        });

        it('웹훅을 놓쳐도 매핑이 만료되면 다음 요청에서 현재 설치를 다시 조회한다', async () => {
            await service.cacheInstallation('my-org', 1);
            store.delete(githubCacheKeys.installation('my-org')); // Redis TTL 만료
            httpService.get.mockReturnValue(of({data: {id: 2}}));

            expect(await installationIdUsed('my-org')).toBe(2);
        });
    });

    it('installation 조회의 4xx 에러는 GithubClientError로 변환한다', async () => {
        redis.get.mockResolvedValue(null);
        httpService.get.mockReturnValueOnce(
            throwError(() => createAxiosError(404, 'Installation not found')),
        );

        await expect(service.getInstallationToken('my-org')).rejects.toEqual(
            new GithubClientError('Installation not found', 404),
        );
        expect(httpService.post).not.toHaveBeenCalled();
    });

    it('토큰 발급의 4xx 에러는 GithubClientError로 변환한다', async () => {
        redis.get.mockResolvedValue(null);
        httpService.get.mockReturnValue(of({data: {id: 555}}));
        httpService.post.mockReturnValueOnce(
            throwError(() => createAxiosError(403, 'Forbidden')),
        );

        await expect(service.getInstallationToken('my-org')).rejects.toEqual(
            new GithubClientError('Forbidden', 403),
        );
    });

    it('installation의 계정 login을 조회한다', async () => {
        httpService.get.mockReturnValue(
            of({data: {account: {login: 'my-org'}}}),
        );

        const login = await service.getInstallationAccountLogin(42);

        expect(login).toBe('my-org');
        expect(httpService.get).toHaveBeenCalledWith(
            'https://api.github.com/app/installations/42',
            expect.objectContaining({
                headers: expect.objectContaining({
                    Authorization: 'Bearer mock-jwt',
                }) as unknown,
            }),
        );
    });

    it('installation 계정 조회의 4xx 에러는 GithubClientError로 변환한다', async () => {
        httpService.get.mockReturnValueOnce(
            throwError(() => createAxiosError(404, 'Installation not found')),
        );

        await expect(service.getInstallationAccountLogin(42)).rejects.toEqual(
            new GithubClientError('Installation not found', 404),
        );
    });
});
