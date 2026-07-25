import { ConfigService } from '@nestjs/config';
import { IoAdapter } from '@nestjs/platform-socket.io';
import { RedisIoAdapter } from './redis-io.adapter';

const mockOn = jest.fn();
const mockOnce = jest.fn((event: string, cb: () => void) => {
    if (event === 'ready') cb();
});
const mockDuplicate = jest.fn();

function createMockRedisInstance() {
    return { on: mockOn, once: mockOnce, duplicate: mockDuplicate };
}

jest.mock('ioredis', () => jest.fn().mockImplementation(() => createMockRedisInstance()));

const mockConfigService = {
    get: jest.fn((key: string) => {
        if (key === 'REDIS_HOST') return 'localhost';
        if (key === 'REDIS_PORT') return '6379';
        return undefined;
    }),
} as unknown as ConfigService;

describe('RedisIoAdapter', () => {
    beforeEach(() => {
        jest.clearAllMocks();
        mockDuplicate.mockImplementation(() => createMockRedisInstance());
        mockOnce.mockImplementation((event: string, cb: () => void) => {
            if (event === 'ready') cb();
        });
    });

    describe('connectToRedis', () => {
        it('pub/sub 클라이언트가 모두 준비되면 정상적으로 완료된다', async () => {
            const adapter = new RedisIoAdapter({} as never);

            await expect(adapter.connectToRedis(mockConfigService)).resolves.toBeUndefined();
        });

        it('REDIS_PORT가 없으면 기본값 6379를 사용한다', async () => {
            const configWithoutPort = {
                get: jest.fn((key: string) => (key === 'REDIS_HOST' ? 'localhost' : undefined)),
            } as unknown as ConfigService;
            const adapter = new RedisIoAdapter({} as never);

            await expect(adapter.connectToRedis(configWithoutPort)).resolves.toBeUndefined();
        });

        it('Redis 클라이언트에서 error 이벤트가 발생하면 예외를 던지지 않고 in-memory로 폴백한다', async () => {
            mockOnce.mockImplementation((event: string, cb: (err?: Error) => void) => {
                if (event === 'error') cb(new Error('connection refused'));
            });
            const fakeServer = { adapter: jest.fn() };
            jest.spyOn(IoAdapter.prototype, 'createIOServer').mockReturnValue(fakeServer);

            const adapter = new RedisIoAdapter({} as never);
            await expect(adapter.connectToRedis(mockConfigService)).resolves.toBeUndefined();
            adapter.createIOServer(0);

            expect(fakeServer.adapter).not.toHaveBeenCalled();
        });

        it('READY_TIMEOUT_MS 내에 준비되지 않으면 부팅을 막지 않고 in-memory로 폴백한다', async () => {
            jest.useFakeTimers();
            mockOnce.mockImplementation(() => {
                // ready/error 둘 다 발생하지 않는 상황(Redis 미응답)을 시뮬레이션
            });
            const fakeServer = { adapter: jest.fn() };
            jest.spyOn(IoAdapter.prototype, 'createIOServer').mockReturnValue(fakeServer);

            const adapter = new RedisIoAdapter({} as never);
            const connectPromise = adapter.connectToRedis(mockConfigService);
            await jest.advanceTimersByTimeAsync(5_000);
            await expect(connectPromise).resolves.toBeUndefined();
            adapter.createIOServer(0);

            expect(fakeServer.adapter).not.toHaveBeenCalled();
            jest.useRealTimers();
        });
    });

    describe('createIOServer', () => {
        it('connectToRedis 완료 후에는 Redis 기반 adapter factory를 서버에 적용한다', async () => {
            const fakeServer = { adapter: jest.fn() };
            jest.spyOn(IoAdapter.prototype, 'createIOServer').mockReturnValue(fakeServer);

            const adapter = new RedisIoAdapter({} as never);
            await adapter.connectToRedis(mockConfigService);
            const server = adapter.createIOServer(0);

            expect(fakeServer.adapter).toHaveBeenCalledTimes(1);
            expect(fakeServer.adapter).toHaveBeenCalledWith(expect.any(Function));
            expect(server).toBe(fakeServer);
        });

        it('connectToRedis를 호출하지 않았으면 기본 in-memory adapter로 폴백한다', () => {
            const fakeServer = { adapter: jest.fn() };
            jest.spyOn(IoAdapter.prototype, 'createIOServer').mockReturnValue(fakeServer);

            const adapter = new RedisIoAdapter({} as never);
            adapter.createIOServer(0);

            expect(fakeServer.adapter).not.toHaveBeenCalled();
        });
    });
});
