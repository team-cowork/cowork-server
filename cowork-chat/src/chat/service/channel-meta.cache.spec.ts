import { ConfigService } from '@nestjs/config';
import { ChannelMetaCache } from './channel-meta.cache';

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

describe('ChannelMetaCache', () => {
    let cache: ChannelMetaCache;

    beforeEach(() => {
        jest.clearAllMocks();
        mockConnect.mockResolvedValue(undefined);
        cache = new ChannelMetaCache(mockConfigService);
        cache.onModuleInit();
    });

    describe('get', () => {
        it('캐시에 값이 없으면 null을 반환한다', async () => {
            mockGet.mockResolvedValue(null);

            await expect(cache.get(5)).resolves.toBeNull();
            expect(mockGet).toHaveBeenCalledWith('channel:meta:5');
        });

        it('캐시에 값이 있으면 viewType 문자열을 반환한다', async () => {
            mockGet.mockResolvedValue('FILE_SHARE');

            await expect(cache.get(5)).resolves.toBe('FILE_SHARE');
        });

        it('Redis 오류가 발생하면 캐시 미스(null)로 처리한다', async () => {
            mockGet.mockRejectedValue(new Error('connection lost'));

            await expect(cache.get(5)).resolves.toBeNull();
        });
    });

    describe('set', () => {
        it('viewType을 TTL 30초로 저장한다', async () => {
            await cache.set(5, 'FILE_SHARE');

            expect(mockSet).toHaveBeenCalledWith('channel:meta:5', 'FILE_SHARE', 'EX', 30);
        });

        it('Redis 오류가 발생해도 예외를 던지지 않는다', async () => {
            mockSet.mockRejectedValue(new Error('connection lost'));

            await expect(cache.set(5, 'FILE_SHARE')).resolves.toBeUndefined();
        });
    });

    describe('invalidate', () => {
        it('캐시 키를 삭제한다', async () => {
            await cache.invalidate(5);

            expect(mockDel).toHaveBeenCalledWith('channel:meta:5');
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
