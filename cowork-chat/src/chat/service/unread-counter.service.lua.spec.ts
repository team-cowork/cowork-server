import { ConfigService } from '@nestjs/config';
import { UnreadCounterService } from './unread-counter.service';

// ioredis-mock은 defineCommand로 등록한 Lua 스크립트를 실제 Lua VM(fengari)으로 실행하고
// HEXISTS/HINCRBY 등을 인메모리로 재현한다. 순수 mock(unread-counter.service.spec.ts)과 달리
// 이 스펙은 INCREMENT_IF_PRESENT_SCRIPT 본문의 "필드 존재 시에만 증가" 가드를 실제로 실행해 검증한다.
jest.mock('ioredis', () => jest.requireActual('ioredis-mock') as unknown);

const mockConfigService = {
    get: jest.fn((key: string) => {
        if (key === 'REDIS_HOST') return 'localhost';
        if (key === 'REDIS_PORT') return '6379';
        return undefined;
    }),
} as unknown as ConfigService;

describe('UnreadCounterService (Lua 스크립트 실동작 검증)', () => {
    let service: UnreadCounterService;

    beforeEach(() => {
        service = new UnreadCounterService(mockConfigService);
        service.onModuleInit();
    });

    afterEach(() => {
        service.onModuleDestroy();
    });

    it('필드가 이미 존재하는 유저는 값이 1 증가한다', async () => {
        await service.set(100, 1, 3);

        await service.incrementIfPresent(100, [1]);

        const result = await service.getMany(1, [100]);
        expect(result?.hits.get(100)).toBe(4);
    });

    it('필드가 없는 유저는 증가시키지 않고 캐시 미스로 남는다', async () => {
        await service.incrementIfPresent(200, [2]);

        const result = await service.getMany(2, [200]);
        expect(result?.misses).toEqual([200]);
        expect(result?.hits.has(200)).toBe(false);
    });

    it('여러 유저 중 필드가 존재하는 유저만 증가시킨다', async () => {
        await service.set(300, 10, 5);
        // userId 20에게는 채널 300 필드를 만들지 않는다

        await service.incrementIfPresent(300, [10, 20]);

        const result10 = await service.getMany(10, [300]);
        const result20 = await service.getMany(20, [300]);
        expect(result10?.hits.get(300)).toBe(6);
        expect(result20?.misses).toEqual([300]);
    });
});
