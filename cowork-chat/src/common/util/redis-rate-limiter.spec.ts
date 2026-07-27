import { ConfigService } from '@nestjs/config';
import { RedisRateLimiter } from './redis-rate-limiter';

const mockTryAcquireScript = jest.fn();
const mockDefineCommand = jest.fn();
const mockConnect = jest.fn().mockResolvedValue(undefined);
const mockDisconnect = jest.fn();
const mockOn = jest.fn();

jest.mock('ioredis', () => jest.fn().mockImplementation(() => ({
    tryAcquireScript: mockTryAcquireScript,
    defineCommand: mockDefineCommand,
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

describe('RedisRateLimiter', () => {
    let limiter: RedisRateLimiter;

    beforeEach(() => {
        jest.clearAllMocks();
        mockConnect.mockResolvedValue(undefined);
        limiter = new RedisRateLimiter(mockConfigService);
        limiter.onModuleInit();
    });

    it('onModuleInit 시 tryAcquireScript Lua 스크립트를 등록한다', () => {
        expect(mockDefineCommand).toHaveBeenCalledWith(
            'tryAcquireScript',
            expect.objectContaining({ numberOfKeys: 1, lua: expect.any(String) as unknown }),
        );
    });

    it('한도 내 요청이면 true를 반환한다', async () => {
        mockTryAcquireScript.mockResolvedValue(1);

        await expect(limiter.tryAcquire('chat:msgrate:42', 10_000, 5)).resolves.toBe(true);
        expect(mockTryAcquireScript).toHaveBeenCalledWith(
            'chat:msgrate:42',
            expect.any(Number),
            expect.any(Number),
            expect.any(String),
            10_000,
            5,
        );
    });

    it('시간창 내 개수가 한도 이상이면 false를 반환한다', async () => {
        mockTryAcquireScript.mockResolvedValue(0);

        await expect(limiter.tryAcquire('chat:msgrate:42', 10_000, 5)).resolves.toBe(false);
    });

    it('Redis 오류가 발생하면 fail-open으로 true를 반환한다', async () => {
        mockTryAcquireScript.mockRejectedValue(new Error('connection lost'));

        await expect(limiter.tryAcquire('chat:msgrate:42', 10_000, 5)).resolves.toBe(true);
    });

    it('onModuleDestroy 호출 시 클라이언트 연결을 해제한다', () => {
        limiter.onModuleDestroy();
        expect(mockDisconnect).toHaveBeenCalled();
    });
});
