import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
	Inject as inject,
} from '@nestjs/common';
import {ElasticsearchService} from '../../search/elasticsearch.service.js';
import {
	MessageSearchIndexRepository,
	MessageSearchTombstoneRepository,
} from '../repository/index.js';
import {MessageSearchIndexService} from './message-search-index.service.js';

const POLL_INTERVAL_MS = 3000;
const MESSAGE_BATCH_SIZE = 50;
const TOMBSTONE_BATCH_SIZE = 50;
/**
한 틱에서 가득 찬 배치를 이어 가져올 수 있는 시간. 폴링 간격(3초)보다 짧아야 다음 틱과 겹치지 않는다.
예산은 배치 사이에서만 확인하므로 마지막 배치는 예산을 조금 넘을 수 있다.
*/
const DRAIN_TIME_BUDGET_MS = 2500;
/**
점유 후 이 시간 이상 진행이 없으면 처리 중에 프로세스가 죽은 것으로 보고 회수한다.
*/
const CLAIM_STALE_THRESHOLD_MS = 2 * 60 * 1000;
/**
Tombstone 기록 전에 중단된 삭제 예약을 되돌리기까지 기다리는 시간.
*/
const DELETING_STALE_THRESHOLD_MS = 5 * 60 * 1000;
const RECLAIM_INTERVAL_MS = 60_000;
const METRICS_INTERVAL_MS = 30_000;

/**
 검색 색인 아웃박스 워커.

 대기 중인 색인·삭제 명령을 주기적으로 점유해 Elasticsearch에 반영한다. 점유는 상태 전이로
 이루어지므로 배포 중 구 프로세스가 종료 대기(`kill_timeout`) 동안 진행 중인 주기를 마치는 사이에
 새 프로세스가 같은 항목을 중복 처리하지 않고, 처리 중에 죽은 프로세스가 남긴 점유는 시간이 지나면 회수된다. 색인 쓰기 순서는 메시지별 단조 증가 버전이 정하므로
 배치 내부는 병렬로 처리해도 안전하다.
 */
@injectable()
export class MessageSearchOutboxPoller implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(MessageSearchOutboxPoller.name);
	private timer?: ReturnType<typeof setInterval>;
	private running = false;
	private lastReclaimAt = 0;
	private lastMetricsAt = 0;

	@inject()
	private readonly elasticsearchService!: ElasticsearchService;

	@inject()
	private readonly indexService!: MessageSearchIndexService;

	@inject()
	private readonly indexRepository!: MessageSearchIndexRepository;

	@inject()
	private readonly tombstoneRepository!: MessageSearchTombstoneRepository;

	private async runCycle(): Promise<void> {
		if (this.running) {
			return;
		}

		this.running = true;
		try {
			await this.reclaimIfDue();
			await this.refreshMetricsIfDue();
			if (!await this.elasticsearchService.ensureIndexReady()) {
				return;
			}

			// Tombstone 백로그가 예산을 모두 쓰면 메시지 색인이 틱당 한 배치로 줄어들므로, tombstone에는 절반만 준다.
			const startedAt = Date.now();
			await this.drainWhileFull(async () => this.drainTombstones(), TOMBSTONE_BATCH_SIZE, startedAt + (DRAIN_TIME_BUDGET_MS / 2));
			await this.drainWhileFull(async () => this.drainMessages(), MESSAGE_BATCH_SIZE, startedAt + DRAIN_TIME_BUDGET_MS);
		} catch (error) {
			this.logger.error('Message search index outbox cycle failed', error);
		} finally {
			this.running = false;
		}
	}

	/**
    처리 중에 죽은 프로세스가 남긴 점유와 커밋되지 않은 삭제 예약을 회수한다.
    */
	private async reclaimIfDue(): Promise<void> {
		if (Date.now() - this.lastReclaimAt < RECLAIM_INTERVAL_MS) {
			return;
		}

		this.lastReclaimAt = Date.now();

		const [messages, tombstones] = await Promise.all([
			this.indexRepository.reclaimStaleProcessing(CLAIM_STALE_THRESHOLD_MS),
			this.tombstoneRepository.reclaimStaleProcessing(CLAIM_STALE_THRESHOLD_MS),
		]);
		if (messages > 0 || tombstones > 0) {
			this.logger.warn(`Reclaimed stale search index claims messages=${messages} tombstones=${tombstones}`);
		}

		await this.releaseUncommittedDeletions();
	}

	/**
     `DELETING`으로 예약됐지만 tombstone이 없는 메시지를 다시 색인 대상으로 되돌린다.

     tombstone이 없다는 것은 삭제가 durable하게 기록되기 전에 중단됐다는 뜻이므로,
     MongoDB에 남아 있는 메시지가 최종 상태다.
     */
	private async releaseUncommittedDeletions(): Promise<void> {
		const staleDeleting = await this.indexRepository.findStaleDeleting(DELETING_STALE_THRESHOLD_MS, MESSAGE_BATCH_SIZE);
		if (staleDeleting.length === 0) {
			return;
		}

		const withTombstone = await this.tombstoneRepository.findExistingMessageIds(
			staleDeleting.map(message => message._id.toString()),
		);
		const released = await this.indexRepository.releaseDeleting(
			staleDeleting.filter(message => !withTombstone.has(message._id.toString())),
		);
		if (released > 0) {
			this.logger.warn(`Released ${released} uncommitted message deletion(s) back to search indexing`);
		}
	}

	/**
     배치가 가득 찬 동안 시간 예산 안에서 계속 점유한다. 다음 틱(3초)까지 기다리면 백로그가 쌓인다.
     배치가 가득 차지 않았거나 예산을 넘기면 멈춘다. 각 틱은 최소 한 배치를 처리한다.
     */
	private async drainWhileFull(drainBatch: () => Promise<number>, batchSize: number, deadline: number): Promise<void> {
		if (await drainBatch() === batchSize && Date.now() < deadline) {
			await this.drainWhileFull(drainBatch, batchSize, deadline);
		}
	}

	private async drainTombstones(): Promise<number> {
		const tombstones = await this.tombstoneRepository.claimPending(TOMBSTONE_BATCH_SIZE);
		if (tombstones.length === 0) {
			return 0;
		}

		await this.indexService.applyTombstones(tombstones);
		return tombstones.length;
	}

	private async drainMessages(): Promise<number> {
		const messages = await this.indexRepository.claimPending(MESSAGE_BATCH_SIZE);
		if (messages.length === 0) {
			return 0;
		}

		await this.indexService.applyMessages(messages);
		return messages.length;
	}

	private async refreshMetricsIfDue(): Promise<void> {
		if (Date.now() - this.lastMetricsAt < METRICS_INTERVAL_MS) {
			return;
		}

		this.lastMetricsAt = Date.now();
		await this.indexService.refreshMetrics();
	}

	onModuleInit(): void {
		this.timer = setInterval(() => {
			void this.runCycle();
		}, POLL_INTERVAL_MS);
		this.logger.log('Message search index outbox poller started');
	}

	onModuleDestroy(): void {
		clearInterval(this.timer);
	}
}
