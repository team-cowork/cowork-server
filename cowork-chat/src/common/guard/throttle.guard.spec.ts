import { ExecutionContext, HttpException, HttpStatus } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import { ConfigService } from '@nestjs/config';
import { ThrottleGuard } from './throttle.guard';
import { RedisRateLimiter } from '../util/redis-rate-limiter';
import { ThrottleOptions } from '../decorator/throttle.decorator';

const mockRateLimiter = {
    tryAcquire: jest.fn(),
};

const defaultOptions: ThrottleOptions = {
    key: 'chat:msgrate',
    windowMsConfigKey: 'CHAT_MESSAGE_RATE_LIMIT_WINDOW_MS',
    maxRequestsConfigKey: 'CHAT_MESSAGE_RATE_LIMIT_MAX_REQUESTS',
    defaultWindowMs: 10_000,
    defaultMaxRequests: 20,
};

const makeContext = (headers: Record<string, string> = { 'x-user-id': '42' }): ExecutionContext =>
    ({
        getType: () => 'http',
        getHandler: jest.fn(),
        getClass: jest.fn(),
        switchToHttp: () => ({
            getRequest: () => ({ headers }),
        }),
    }) as unknown as ExecutionContext;

const makeReflector = (options: ThrottleOptions | undefined): Reflector =>
    ({ getAllAndOverride: jest.fn().mockReturnValue(options) }) as unknown as Reflector;

const makeConfigService = (values: Record<string, string> = {}): ConfigService =>
    ({ get: jest.fn((key: string) => values[key]) }) as unknown as ConfigService;

describe('ThrottleGuard', () => {
    beforeEach(() => {
        jest.clearAllMocks();
    });

    describe('canActivate', () => {
        it('요청 횟수가 허용 범위이면 접근을 허용한다', async () => {
            mockRateLimiter.tryAcquire.mockResolvedValue(true);
            const guard = new ThrottleGuard(
                makeReflector(defaultOptions),
                mockRateLimiter as unknown as RedisRateLimiter,
                makeConfigService(),
            );

            await expect(guard.canActivate(makeContext())).resolves.toBe(true);
        });

        it('요청 한도를 초과하면 429로 거부한다', async () => {
            mockRateLimiter.tryAcquire.mockResolvedValue(false);
            const guard = new ThrottleGuard(
                makeReflector(defaultOptions),
                mockRateLimiter as unknown as RedisRateLimiter,
                makeConfigService(),
            );

            await expect(guard.canActivate(makeContext())).rejects.toThrow(HttpException);
            await expect(guard.canActivate(makeContext())).rejects.toMatchObject({
                status: HttpStatus.TOO_MANY_REQUESTS,
            });
        });
    });
});
