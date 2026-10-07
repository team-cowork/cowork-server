import { EventEmitter } from 'node:events';
import type { ConfigService } from '@nestjs/config';
import { nextRedisAdapterState, resolveAdapterMode, SocketIoRedisConnection } from './redis-io.adapter';

const mockRedisClients: MockRedis[] = [];

// eslint-disable-next-line unicorn/prefer-event-target -- Mimics the ioredis client's EventEmitter API.
class MockRedis extends EventEmitter {
    status = 'connecting';
    publish = jest.fn(async () => 0);

    constructor() {
        super();
        mockRedisClients.push(this);
    }

    duplicate() {
        return new MockRedis();
    }

    disconnect() {
        this.status = 'end';
    }

    setStatus(status: string) {
        this.status = status;
        this.emit(status === 'ready' ? 'ready' : 'close');
    }
}

jest.mock('ioredis', () => ({ __esModule: true, default: jest.fn(() => new MockRedis()) }));
jest.mock('@socket.io/redis-adapter', () => ({ createAdapter: jest.fn(() => ({})) }));

const base = {
    stopping: false, installed: true, pubReady: true, subReady: true, wasReady: false,
};

describe('nextRedisAdapterState', () => {
    it('adapter가 설치되고 pub/sub이 모두 준비되면 READY다', () => {
        expect(nextRedisAdapterState(base)).toBe('READY');
    });

    it('한 번도 준비되지 않았으면 pub 또는 sub이 준비되지 않은 동안 CONNECTING이다', () => {
        expect(nextRedisAdapterState({ ...base, subReady: false })).toBe('CONNECTING');
        expect(nextRedisAdapterState({ ...base, pubReady: false })).toBe('CONNECTING');
        expect(nextRedisAdapterState({ ...base, installed: false })).toBe('CONNECTING');
    });

    it('준비된 뒤 pub 또는 sub 한쪽이 끊기면 DEGRADED다', () => {
        expect(nextRedisAdapterState({ ...base, wasReady: true, pubReady: false })).toBe('DEGRADED');
        expect(nextRedisAdapterState({ ...base, wasReady: true, subReady: false })).toBe('DEGRADED');
    });

    it('DEGRADED 뒤 양쪽이 다시 준비되면 READY로 복구된다', () => {
        expect(nextRedisAdapterState({ ...base, wasReady: true })).toBe('READY');
    });

    it('종료 중이면 연결 상태와 무관하게 STOPPED다', () => {
        expect(nextRedisAdapterState({ ...base, stopping: true })).toBe('STOPPED');
    });
});

describe('resolveAdapterMode', () => {
    it('설정이 없으면 redis 모드다', () => {
        expect(resolveAdapterMode(undefined, 'prod')).toBe('redis');
    });

    it('in-memory 모드는 prod가 아닌 profile에서만 허용한다', () => {
        expect(resolveAdapterMode('in-memory', 'local')).toBe('in-memory');
        expect(() => resolveAdapterMode('in-memory', 'prod')).toThrow('not allowed in the prod profile');
    });

    it('알 수 없는 모드는 거부한다', () => {
        expect(() => resolveAdapterMode('memory', 'local')).toThrow('must be \'redis\' or \'in-memory\'');
    });
});

describe('SocketIoRedisConnection.onReadinessChange', () => {
    const configService = { get: (key: string) => (key === 'REDIS_HOST' ? 'localhost' : undefined) } as unknown as ConfigService;

    beforeEach(() => {
        mockRedisClients.length = 0;
    });

    it('준비 여부가 바뀔 때만 한 번씩 알린다', () => {
        const connection = new SocketIoRedisConnection(configService);
        const changes: boolean[] = [];
        connection.onReadinessChange(ready => {
            changes.push(ready);
        });
        connection.createAdapter();
        const [pub, sub] = mockRedisClients;

        pub.setStatus('ready');
        sub.setStatus('ready');
        sub.setStatus('ready');
        pub.setStatus('reconnecting');
        sub.setStatus('reconnecting');
        pub.setStatus('ready');
        sub.setStatus('ready');
        connection.onApplicationShutdown();

        expect(changes).toEqual([true, false, true, false]);
    });
});
