import {type ProjectionReadinessService} from './projection-readiness.service.js';

const PROJECTION_WAIT_HEARTBEAT_INTERVAL_MS = 5000;

/**
 초기 projection 적재가 Kafka session timeout보다 길어져도 consumer가 재조정되지 않도록
 준비 상태를 기다리는 동안 주기적으로 heartbeat를 전송한다.
 */
export async function waitForProjectionReadiness(
	readiness: ProjectionReadinessService,
	heartbeat: () => Promise<void>,
): Promise<void> {
	return new ProjectionReadinessWait(readiness, heartbeat).run();
}

class ProjectionReadinessWait {
	constructor(
		private readonly readiness: ProjectionReadinessService,
		private readonly heartbeat: () => Promise<void>,
	) {}

	/**
     준비될 때까지 `whenReady()` 대기를 반복한다. 이전 대기가 끝난 뒤에만 다음 대기를 시작한다.
     */
	private async waitUntilProjectionReady(): Promise<void> {
		if (this.readiness.isReady()) {
			return;
		}

		await this.waitWithHeartbeat(this.readiness.whenReady());
		return this.waitUntilProjectionReady();
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
		await this.waitWithHeartbeat(this.readiness.checkCatchup());
		await this.waitUntilProjectionReady();
	}
}
