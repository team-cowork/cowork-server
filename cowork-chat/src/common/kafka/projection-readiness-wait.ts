import {setTimeout as delay} from 'node:timers/promises';
import {type ProjectionReadinessService} from './projection-readiness.service.js';
import {type ProjectionName} from './projection-streams.js';

const PROJECTION_WAIT_HEARTBEAT_INTERVAL_MS = 5000;
const PROJECTION_WAIT_RECHECK_INTERVAL_MS = 250;

/**
 초기 projection 적재가 Kafka session timeout보다 길어져도 consumer가 재조정되지 않도록
 준비 상태를 기다리는 동안 주기적으로 heartbeat를 전송한다.

 `streams`를 주지 않으면 모든 stream을 기다린다.
 */
export async function waitForProjectionReadiness(
	readiness: ProjectionReadinessService,
	heartbeat: () => Promise<void>,
	streams?: readonly ProjectionName[],
): Promise<void> {
	return new ProjectionReadinessWait(readiness, heartbeat, streams).run();
}

class ProjectionReadinessWait {
	constructor(
		private readonly readiness: ProjectionReadinessService,
		private readonly heartbeat: () => Promise<void>,
		private readonly streams: readonly ProjectionName[] | undefined,
	) {}

	private isReady(): boolean {
		return this.streams ? this.readiness.areReady(this.streams) : this.readiness.isReady();
	}

	/**
     준비될 때까지 일정 간격으로 다시 확인한다. `whenReady()`는 모든 stream 기준이라 일부 stream만 기다릴 수 없고,
     매번 타이머로 양보해야 준비 상태를 갱신하는 poll과 I/O가 이벤트 루프에서 실행된다.
     */
	private async pollUntilReady(): Promise<void> {
		if (this.isReady()) {
			return;
		}

		await delay(PROJECTION_WAIT_RECHECK_INTERVAL_MS);
		return this.pollUntilReady();
	}

	/**
     작업이 끝날 때까지 heartbeat 간격마다 heartbeat를 보낸다. 각 heartbeat가 끝난 뒤에 다음 간격을 잰다.
     */
	private async waitWithHeartbeat(operation: Promise<void>): Promise<void> {
		if (await this.settlesBeforeHeartbeat(operation)) {
			return;
		}

		await this.heartbeat();
		return this.waitWithHeartbeat(operation);
	}

	private async settlesBeforeHeartbeat(operation: Promise<void>): Promise<boolean> {
		let timer: ReturnType<typeof setTimeout> | undefined;
		const completed = operation.then(() => true);
		const heartbeatInterval = new Promise<boolean>(resolve => {
			timer = setTimeout(() => {
				resolve(false);
			}, PROJECTION_WAIT_HEARTBEAT_INTERVAL_MS);
		});

		try {
			return await Promise.race([completed, heartbeatInterval]);
		} finally {
			if (timer) {
				clearTimeout(timer);
			}
		}
	}

	async run(): Promise<void> {
		// 준비된 동안에는 1초 poll이 상태를 갱신하므로, record마다 전체 catch-up 검사를 다시 하지 않는다.
		if (this.isReady()) {
			return;
		}

		await this.waitWithHeartbeat(this.readiness.checkCatchup());
		await this.waitWithHeartbeat(this.pollUntilReady());
	}
}
