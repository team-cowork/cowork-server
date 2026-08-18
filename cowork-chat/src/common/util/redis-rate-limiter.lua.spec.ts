import { ConfigService } from '@nestjs/config';
import { RedisRateLimiter } from './redis-rate-limiter';

// ioredis-mock은 defineCommand로 등록한 Lua 스크립트를 실제 Lua VM(fengari)으로 실행하고
// ZADD/ZCARD/ZREMRANGEBYSCORE 등을 인메모리로 재현한다. 순수 mock(redis-rate-limiter.spec.ts)과 달리
// 이 스펙은 TRY_ACQUIRE_SCRIPT 본문의 임계값 비교·윈도우 로직 자체를 실제로 실행해 검증한다.
jest.mock('ioredis', () => jest.requireActual('ioredis-mock') as unknown);

const mockConfigService = {
    get: jest.fn((key: string) => {
        if (key === 'REDIS_HOST') return 'localhost';
        if (key === 'REDIS_PORT') return '6379';
        return undefined;
    }),
} as unknown as ConfigService;

describe('RedisRateLimiter (Lua 스크립트 실동작 검증)', () => {
    let limiter: RedisRateLimiter;

    beforeEach(() => {
        limiter = new RedisRateLimiter(mockConfigService);
        limiter.onModuleInit();
    });

    afterEach(() => {
        limiter.onModuleDestroy();
    });

    it('한도 내 요청은 계속 허용된다', async () => {
        for (let i = 0; i < 5; i++) {
            await expect(limiter.tryAcquire('rl:lua:1', 10_000, 5)).resolves.toBe(true);
        }
    });

    it('정확히 한도에 도달하면 그 다음 요청부터는 거부된다', async () => {
        await expect(limiter.tryAcquire('rl:lua:2', 10_000, 3)).resolves.toBe(true);
        await expect(limiter.tryAcquire('rl:lua:2', 10_000, 3)).resolves.toBe(true);
        await expect(limiter.tryAcquire('rl:lua:2', 10_000, 3)).resolves.toBe(true);

        await expect(limiter.tryAcquire('rl:lua:2', 10_000, 3)).resolves.toBe(false);
        await expect(limiter.tryAcquire('rl:lua:2', 10_000, 3)).resolves.toBe(false);
    });

    it('윈도우 시간이 지나면 다시 허용된다', async () => {
        for (let i = 0; i < 5; i++) {
            await limiter.tryAcquire('rl:lua:3', 150, 5);
        }
        await expect(limiter.tryAcquire('rl:lua:3', 150, 5)).resolves.toBe(false);

        await new Promise((resolve) => setTimeout(resolve, 250));

        await expect(limiter.tryAcquire('rl:lua:3', 150, 5)).resolves.toBe(true);
    });

    it('서로 다른 key는 독립적으로 카운트된다', async () => {
        for (let i = 0; i < 5; i++) {
            await limiter.tryAcquire('rl:lua:4a', 10_000, 5);
        }
        await expect(limiter.tryAcquire('rl:lua:4a', 10_000, 5)).resolves.toBe(false);
        await expect(limiter.tryAcquire('rl:lua:4b', 10_000, 5)).resolves.toBe(true);
    });
});
