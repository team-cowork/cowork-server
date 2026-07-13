import { ConfigService } from '@nestjs/config';
import { ProjectRepoCache } from './project-repo.cache';

const mockGet = jest.fn();
const mockSet = jest.fn().mockResolvedValue(undefined);
const mockDel = jest.fn().mockResolvedValue(undefined);
const mockConnect = jest.fn().mockResolvedValue(undefined);
const mockDisconnect = jest.fn();
const mockOn = jest.fn();

jest.mock('ioredis', () => jest.fn().mockImplementation(() => ({
    get: mockGet,
    set: mockSet,
    del: mockDel,
    connect: mockConnect,
    disconnect: mockDisconnect,
    on: mockOn,
})));

const mockConfigService = {
    get: jest.fn((key: string) => {
        if (key === 'REDIS_HOST') return 'localhost';
        if (key === 'REDIS_PORT') return '6379';
        return undefined;
    }),
} as unknown as ConfigService;

describe('ProjectRepoCache', () => {
    let cache: ProjectRepoCache;

    beforeEach(() => {
        jest.clearAllMocks();
        mockConnect.mockResolvedValue(undefined);
        cache = new ProjectRepoCache(mockConfigService);
        cache.onModuleInit();
    });

    describe('get', () => {
        it('캐시에 값이 없으면 undefined를 반환한다', async () => {
            mockGet.mockResolvedValue(null);

            await expect(cache.get(5)).resolves.toBeUndefined();
            expect(mockGet).toHaveBeenCalledWith('project:repo:5');
        });

        it('캐시된 저장소 정보를 파싱해 반환한다', async () => {
            mockGet.mockResolvedValue(JSON.stringify({ teamId: 10, owner: 'my-org', repo: 'backend' }));

            await expect(cache.get(5)).resolves.toEqual({ teamId: 10, owner: 'my-org', repo: 'backend' });
        });

        it('저장소 정보 없음(null)이 캐시되어 있으면 null을 반환한다', async () => {
            mockGet.mockResolvedValue('null');

            await expect(cache.get(5)).resolves.toBeNull();
        });

        it('Redis 오류가 발생하면 캐시 미스(undefined)로 처리한다', async () => {
            mockGet.mockRejectedValue(new Error('connection lost'));

            await expect(cache.get(5)).resolves.toBeUndefined();
        });
    });

    describe('set', () => {
        it('저장소 정보를 TTL 30초로 저장한다', async () => {
            await cache.set(5, { teamId: 10, owner: 'my-org', repo: 'backend' });

            expect(mockSet).toHaveBeenCalledWith(
                'project:repo:5',
                JSON.stringify({ teamId: 10, owner: 'my-org', repo: 'backend' }),
                'EX',
                30,
            );
        });

        it('null도 TTL 30초로 저장한다', async () => {
            await cache.set(5, null);

            expect(mockSet).toHaveBeenCalledWith('project:repo:5', 'null', 'EX', 30);
        });

        it('Redis 오류가 발생해도 예외를 던지지 않는다', async () => {
            mockSet.mockRejectedValue(new Error('connection lost'));

            await expect(cache.set(5, null)).resolves.toBeUndefined();
        });
    });

    describe('invalidate', () => {
        it('캐시 키를 삭제한다', async () => {
            await cache.invalidate(5);

            expect(mockDel).toHaveBeenCalledWith('project:repo:5');
        });

        it('Redis 오류가 발생해도 예외를 던지지 않는다', async () => {
            mockDel.mockRejectedValue(new Error('connection lost'));

            await expect(cache.invalidate(5)).resolves.toBeUndefined();
        });
    });

    it('onModuleDestroy 호출 시 클라이언트 연결을 해제한다', () => {
        cache.onModuleDestroy();
        expect(mockDisconnect).toHaveBeenCalled();
    });
});
