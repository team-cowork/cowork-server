import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ElasticsearchService } from '../../search/elasticsearch.service';
import { MessageSearchIndexRepository } from '../repository/message-search-index.repository';
import { MessageSearchTombstoneRepository } from '../repository/message-search-tombstone.repository';
import { MessageSearchIndexStateRepository } from '../repository/message-search-index-state.repository';
import { MessageSearchIndexService } from './message-search-index.service';

const POLL_INTERVAL_MS = 3_000;
const MESSAGE_BATCH_SIZE = 50;
const TOMBSTONE_BATCH_SIZE = 50;
const LEGACY_BACKFILL_BATCH_SIZE = 500;
/** 점유 후 이 시간 이상 진행이 없으면 다른 replica가 회수한다. */
const CLAIM_STALE_THRESHOLD_MS = 2 * 60 * 1_000;
/** tombstone 기록 전에 중단된 삭제 예약을 되돌리기까지 기다리는 시간. */
const DELETING_STALE_THRESHOLD_MS = 5 * 60 * 1_000;
const RECLAIM_INTERVAL_MS = 60_000;
const METRICS_INTERVAL_MS = 30_000;

/**
 * 검색 색인 아웃박스 워커.
 *
 * 대기 중인 색인·삭제 명령을 주기적으로 점유해 Elasticsearch에 반영한다. 점유는 상태 전이로
 * 이루어지므로 여러 replica가 동시에 돌아도 같은 항목을 중복 처리하지 않고, 중단된 replica가
 * 남긴 점유는 시간이 지나면 회수된다. 색인 쓰기 순서는 메시지별 단조 증가 버전이 정하므로
 * 배치 내부는 병렬로 처리해도 안전하다.
 */
@Injectable()
export class MessageSearchOutboxPoller implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(MessageSearchOutboxPoller.name);
    private timer?: ReturnType<typeof setInterval>;
    private running = false;
    private lastReclaimAt = 0;
    private lastMetricsAt = 0;
    private legacyBackfillDone = false;

    constructor(
        private readonly elasticsearchService: ElasticsearchService,
        private readonly indexService: MessageSearchIndexService,
        private readonly indexRepository: MessageSearchIndexRepository,
        private readonly tombstoneRepository: MessageSearchTombstoneRepository,
        private readonly stateRepository: MessageSearchIndexStateRepository,
    ) {}

    onModuleInit(): void {
        this.timer = setInterval(() => { void this.runCycle(); }, POLL_INTERVAL_MS);
        this.logger.log('Message search index outbox poller started');
    }

    onModuleDestroy(): void {
        clearInterval(this.timer);
    }

    private async runCycle(): Promise<void> {
        if (this.running) return;
        this.running = true;
        try {
            await this.reclaimIfDue();
            await this.refreshMetricsIfDue();
            if (!await this.elasticsearchService.ensureIndexReady()) return;
            await this.backfillLegacyState();
            await this.drainTombstones();
            await this.drainMessages();
        } catch (error) {
            this.logger.error('Message search index outbox cycle failed', error);
        } finally {
            this.running = false;
        }
    }

    /** 중단된 replica가 남긴 점유와 커밋되지 않은 삭제 예약을 회수한다. */
    private async reclaimIfDue(): Promise<void> {
        if (Date.now() - this.lastReclaimAt < RECLAIM_INTERVAL_MS) return;
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
     * `DELETING`으로 예약됐지만 tombstone이 없는 메시지를 다시 색인 대상으로 되돌린다.
     *
     * tombstone이 없다는 것은 삭제가 durable하게 기록되기 전에 중단됐다는 뜻이므로,
     * MongoDB에 남아 있는 메시지가 최종 상태다.
     */
    private async releaseUncommittedDeletions(): Promise<void> {
        const staleIds = await this.indexRepository.findStaleDeleting(DELETING_STALE_THRESHOLD_MS, MESSAGE_BATCH_SIZE);
        if (staleIds.length === 0) return;

        const withTombstone = await this.tombstoneRepository.findExistingMessageIds(staleIds.map((id) => id.toString()));
        const released = await this.indexRepository.releaseDeleting(
            staleIds.filter((id) => !withTombstone.has(id.toString())),
        );
        if (released > 0) {
            this.logger.warn(`Released ${released} uncommitted message deletion(s) back to search indexing`);
        }
    }

    /**
     * 아웃박스 필드가 없는 레거시 메시지를 배치로 백필한다.
     *
     * 백필된 메시지는 일반 대기 항목이 되어 워커가 색인을 복원하므로, 이 서비스 도입 이전에
     * 누락된 색인도 전체 재구축 없이 수렴한다.
     */
    private async backfillLegacyState(): Promise<void> {
        if (this.legacyBackfillDone) return;
        const filled = await this.indexRepository.backfillLegacyState(LEGACY_BACKFILL_BATCH_SIZE);
        if (filled > 0) {
            this.logger.log(`Backfilled search index state for ${filled} legacy message(s)`);
            return;
        }
        this.legacyBackfillDone = true;
        await this.stateRepository.markLegacyBackfillCompleted();
        this.logger.log('Legacy search index state backfill completed');
    }

    private async drainTombstones(): Promise<void> {
        const tombstones = await this.tombstoneRepository.claimPending(TOMBSTONE_BATCH_SIZE);
        if (tombstones.length === 0) return;
        await Promise.all(tombstones.map((tombstone) => this.indexService.applyTombstone(tombstone)));
    }

    private async drainMessages(): Promise<void> {
        const messages = await this.indexRepository.claimPending(MESSAGE_BATCH_SIZE);
        if (messages.length === 0) return;
        await Promise.all(messages.map((message) => this.indexService.applyMessage(message)));
    }

    private async refreshMetricsIfDue(): Promise<void> {
        if (Date.now() - this.lastMetricsAt < METRICS_INTERVAL_MS) return;
        this.lastMetricsAt = Date.now();
        await this.indexService.refreshMetrics();
    }
}
