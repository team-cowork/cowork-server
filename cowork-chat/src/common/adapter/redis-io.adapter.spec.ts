import { nextRedisAdapterState, resolveAdapterMode } from './redis-io.adapter';

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
