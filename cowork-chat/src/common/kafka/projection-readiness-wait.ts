import {setInterval} from 'node:timers/promises';
import {type ProjectionReadinessService} from './projection-readiness.service.js';
import {type ProjectionName} from './projection-streams.js';

const PROJECTION_WAIT_RECHECK_INTERVAL_MS = 250;

/**
 Projection이 준비될 때까지 기다린다. Consumer group heartbeat는 Kafka client가 처리와 무관하게 보내므로 여기서 보내지 않는다.

 `streams`를 주지 않으면 모든 stream을 기다린다.
 */
export async function waitForProjectionReadiness(
	readiness: ProjectionReadinessService,
	streams?: readonly ProjectionName[],
): Promise<void> {
	const isReady = () => (streams ? readiness.areReady(streams) : readiness.isReady());
	// 준비된 동안에는 1초 poll이 상태를 갱신하므로, record마다 전체 catch-up 검사를 다시 하지 않는다.
	if (isReady()) {
		return;
	}

	await readiness.checkCatchup();
	// `whenReady()`는 모든 stream 기준이라 일부 stream만 기다릴 수 없고, 매번 타이머로 양보해야 준비 상태를 갱신하는 poll과 I/O가 이벤트 루프에서 실행된다.
	for await (const _tick of setInterval(PROJECTION_WAIT_RECHECK_INTERVAL_MS)) {
		if (isReady()) {
			return;
		}
	}
}
