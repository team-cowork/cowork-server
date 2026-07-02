import { ConfigService } from '@nestjs/config';
import { RedisRateLimiter } from './redis-rate-limiter';

const mockExec = jest.fn();
const mockZrem = jest.fn();
const mockPipeline = {
    zremrangebyscore: jest.fn().mockReturnThis(),
    zcard: jest.fn().mockReturnThis(),
    zadd: jest.fn().mockReturnThis(),
    pexpire: jest.fn().mockReturnThis(),
    exec: mockExec,
};
const mockMulti = jest.fn(() => mockPipeline);
const mockConnect = jest.fn().mockResolvedValue(undefined);
const mockDisconnect = jest.fn();

jest.mock('ioredis', () => jest.fn().mockImplementation(() => ({
    multi: mockMulti,
    zrem: mockZrem,
    connect: mockConnect,
    disconnect: mockDisconnect,
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

    it('한도 내 요청이면 true를 반환하고 방금 추가한 항목을 제거하지 않는다', async () => {
        mockExec.mockResolvedValue([
            [null, 0],
            [null, 1],
            [null, 1],
            [null, 1],
        ]);

        await expect(limiter.tryAcquire('chat:msgrate:42', 10_000, 5)).resolves.toBe(true);
        expect(mockZrem).not.toHaveBeenCalled();
    });

    it('시간창 내 개수가 한도 이상이면 false를 반환하고 방금 추가한 항목을 제거한다', async () => {
        mockExec.mockResolvedValue([
            [null, 0],
            [null, 5],
            [null, 1],
            [null, 1],
        ]);

        await expect(limiter.tryAcquire('chat:msgrate:42', 10_000, 5)).resolves.toBe(false);
        expect(mockZrem).toHaveBeenCalledWith('chat:msgrate:42', expect.any(String));
    });

    it('Redis 오류가 발생하면 fail-open으로 true를 반환한다', async () => {
        mockExec.mockRejectedValue(new Error('connection lost'));

        await expect(limiter.tryAcquire('chat:msgrate:42', 10_000, 5)).resolves.toBe(true);
    });

    it('onModuleDestroy 호출 시 클라이언트 연결을 해제한다', () => {
        limiter.onModuleDestroy();
        expect(mockDisconnect).toHaveBeenCalled();
    });
});
