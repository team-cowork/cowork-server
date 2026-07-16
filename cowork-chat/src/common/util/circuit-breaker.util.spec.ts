import { CircuitBreakerUtil } from './circuit-breaker.util';

describe('CircuitBreakerUtil', () => {
    beforeEach(() => {
        jest.useFakeTimers();
    });

    afterEach(() => {
        jest.useRealTimers();
    });

    it('CLOSED 상태에서는 항상 요청을 허용한다', () => {
        const service = 'service-closed';

        expect(CircuitBreakerUtil.canRequest(service)).toBe(true);
        CircuitBreakerUtil.onSuccess(service);
        expect(CircuitBreakerUtil.canRequest(service)).toBe(true);
    });

    it('연속 실패가 임계치를 넘으면 OPEN으로 전환되어 이후 요청을 즉시 차단한다', () => {
        const service = 'service-open';

        for (let i = 0; i < 3; i++) {
            CircuitBreakerUtil.onFailure(service, 3);
        }

        expect(CircuitBreakerUtil.canRequest(service)).toBe(false);
    });

    it('OPEN 유지 시간이 지나면 HALF_OPEN으로 전환되어 단 1회 probe만 허용한다', () => {
        const service = 'service-half-open';

        for (let i = 0; i < 3; i++) {
            CircuitBreakerUtil.onFailure(service, 3);
        }
        expect(CircuitBreakerUtil.canRequest(service, 1000)).toBe(false);

        jest.advanceTimersByTime(1000);

        expect(CircuitBreakerUtil.canRequest(service, 1000)).toBe(true);
        // HALF_OPEN에서는 동시에 하나의 probe만 허용된다.
        expect(CircuitBreakerUtil.canRequest(service, 1000)).toBe(false);
    });

    it('HALF_OPEN에서 성공하면 CLOSED로 복구된다', () => {
        const service = 'service-half-open-success';

        for (let i = 0; i < 3; i++) {
            CircuitBreakerUtil.onFailure(service, 3);
        }
        jest.advanceTimersByTime(1000);
        expect(CircuitBreakerUtil.canRequest(service, 1000)).toBe(true);

        CircuitBreakerUtil.onSuccess(service);

        expect(CircuitBreakerUtil.canRequest(service, 1000)).toBe(true);
    });

    it('HALF_OPEN에서 실패하면 다시 OPEN으로 전환된다', () => {
        const service = 'service-half-open-failure';

        for (let i = 0; i < 3; i++) {
            CircuitBreakerUtil.onFailure(service, 3);
        }
        jest.advanceTimersByTime(1000);
        expect(CircuitBreakerUtil.canRequest(service, 1000)).toBe(true);

        CircuitBreakerUtil.onFailure(service, 3);

        expect(CircuitBreakerUtil.canRequest(service, 1000)).toBe(false);
    });

    it('서비스 이름별로 상태가 독립적으로 관리된다', () => {
        const serviceA = 'service-a';
        const serviceB = 'service-b';

        for (let i = 0; i < 3; i++) {
            CircuitBreakerUtil.onFailure(serviceA, 3);
        }

        expect(CircuitBreakerUtil.canRequest(serviceA)).toBe(false);
        expect(CircuitBreakerUtil.canRequest(serviceB)).toBe(true);
    });
});
