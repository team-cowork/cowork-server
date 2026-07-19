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

    it('HTTP 컨텍스트가 아니면 통과시키고 rate limiter를 호출하지 않는다', async () => {
        const wsContext = {
            getType: () => 'ws',
            getHandler: jest.fn(),
            getClass: jest.fn(),
        } as unknown as ExecutionContext;
        const guard = new ThrottleGuard(makeReflector(defaultOptions), mockRateLimiter as unknown as RedisRateLimiter, makeConfigService());

        await expect(guard.canActivate(wsContext)).resolves.toBe(true);
        expect(mockRateLimiter.tryAcquire).not.toHaveBeenCalled();
    });

    it('데코레이터 메타데이터가 없으면 통과시키고 rate limiter를 호출하지 않는다', async () => {
        const guard = new ThrottleGuard(makeReflector(undefined), mockRateLimiter as unknown as RedisRateLimiter, makeConfigService());

        await expect(guard.canActivate(makeContext())).resolves.toBe(true);
        expect(mockRateLimiter.tryAcquire).not.toHaveBeenCalled();
    });

    it('한도 내이면 통과시키고 {key}:{userId} 형태의 키로 조회한다', async () => {
        mockRateLimiter.tryAcquire.mockResolvedValue(true);
        const guard = new ThrottleGuard(makeReflector(defaultOptions), mockRateLimiter as unknown as RedisRateLimiter, makeConfigService());

        await expect(guard.canActivate(makeContext())).resolves.toBe(true);
        expect(mockRateLimiter.tryAcquire).toHaveBeenCalledWith('chat:msgrate:42', 10_000, 20);
    });

    it('한도를 초과하면 HttpException(429)을 던진다', async () => {
        mockRateLimiter.tryAcquire.mockResolvedValue(false);
        const guard = new ThrottleGuard(makeReflector(defaultOptions), mockRateLimiter as unknown as RedisRateLimiter, makeConfigService());

        await expect(guard.canActivate(makeContext())).rejects.toThrow(HttpException);
        await expect(guard.canActivate(makeContext())).rejects.toMatchObject({ status: HttpStatus.TOO_MANY_REQUESTS });
    });

    it('환경설정 값이 있으면 default 대신 해당 값을 사용한다', async () => {
        mockRateLimiter.tryAcquire.mockResolvedValue(true);
        const configService = makeConfigService({ CHAT_MESSAGE_RATE_LIMIT_MAX_REQUESTS: '5' });
        const guard = new ThrottleGuard(makeReflector(defaultOptions), mockRateLimiter as unknown as RedisRateLimiter, configService);

        await guard.canActivate(makeContext());

        expect(mockRateLimiter.tryAcquire).toHaveBeenCalledWith('chat:msgrate:42', 10_000, 5);
    });

    it('환경설정 값이 숫자로 파싱되지 않으면 default 값으로 폴백한다', async () => {
        mockRateLimiter.tryAcquire.mockResolvedValue(true);
        const configService = makeConfigService({ CHAT_MESSAGE_RATE_LIMIT_MAX_REQUESTS: 'not-a-number' });
        const guard = new ThrottleGuard(makeReflector(defaultOptions), mockRateLimiter as unknown as RedisRateLimiter, configService);

        await guard.canActivate(makeContext());

        expect(mockRateLimiter.tryAcquire).toHaveBeenCalledWith('chat:msgrate:42', 10_000, 20);
    });
});
