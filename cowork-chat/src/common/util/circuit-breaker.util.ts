const DEFAULT_FAILURE_THRESHOLD = 5;
const DEFAULT_OPEN_DURATION_MS = 30_000;

type CircuitState = 'CLOSED' | 'OPEN' | 'HALF_OPEN';

interface CircuitEntry {
    state: CircuitState;
    failureCount: number;
    openedAt: number;
    halfOpenProbeInFlight: boolean;
}

/**
 * 서비스 이름 단위로 상태를 격리하는 경량 circuit breaker.
 *
 * CLOSED → (연속 실패 임계치 초과) → OPEN → (일정 시간 경과) → HALF_OPEN → (probe 성공/실패) → CLOSED/OPEN
 */
export class CircuitBreakerUtil {
    private static readonly entries = new Map<string, CircuitEntry>();

    /** 요청을 시도해도 되는지 확인한다. OPEN 상태에서 대기 시간이 지나면 HALF_OPEN으로 전환해 단 1회 probe를 허용한다. */
    static canRequest(serviceName: string, openDurationMs: number = DEFAULT_OPEN_DURATION_MS): boolean {
        const entry = CircuitBreakerUtil.getEntry(serviceName);

        if (entry.state === 'CLOSED') return true;

        if (entry.state === 'OPEN') {
            if (Date.now() - entry.openedAt < openDurationMs) return false;
            entry.state = 'HALF_OPEN';
            entry.halfOpenProbeInFlight = true;
            return true;
        }

        // HALF_OPEN: 동시에 여러 probe가 나가지 않도록 하나만 허용한다.
        if (entry.halfOpenProbeInFlight) {
            return false;
        }
        entry.halfOpenProbeInFlight = true;
        return true;
    }

    /** 요청 성공을 기록한다. HALF_OPEN이었다면 CLOSED로 복구한다. */
    static onSuccess(serviceName: string): void {
        const entry = CircuitBreakerUtil.getEntry(serviceName);
        entry.state = 'CLOSED';
        entry.failureCount = 0;
        entry.halfOpenProbeInFlight = false;
    }

    /** 요청 실패를 기록한다. HALF_OPEN에서 실패하면 즉시 OPEN으로 되돌아가고, CLOSED에서는 임계치 초과 시 OPEN으로 전환한다. */
    static onFailure(serviceName: string, failureThreshold: number = DEFAULT_FAILURE_THRESHOLD): void {
        const entry = CircuitBreakerUtil.getEntry(serviceName);

        if (entry.state === 'HALF_OPEN') {
            entry.state = 'OPEN';
            entry.openedAt = Date.now();
            entry.halfOpenProbeInFlight = false;
            return;
        }

        entry.failureCount += 1;
        if (entry.failureCount >= failureThreshold) {
            entry.state = 'OPEN';
            entry.openedAt = Date.now();
            entry.failureCount = 0;
        }
    }

    private static getEntry(serviceName: string): CircuitEntry {
        let entry = CircuitBreakerUtil.entries.get(serviceName);
        if (!entry) {
            entry = { state: 'CLOSED', failureCount: 0, openedAt: 0, halfOpenProbeInFlight: false };
            CircuitBreakerUtil.entries.set(serviceName, entry);
        }
        return entry;
    }
}
