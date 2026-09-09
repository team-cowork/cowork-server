import { randomUUID } from 'crypto';
import { Injectable, Logger } from '@nestjs/common';
import { Types } from 'mongoose';
import { ElasticsearchService } from '../../search/elasticsearch.service';
import {
    buildMessageIndexName,
    errorMessageOf,
    isManagedMessageIndex,
    MESSAGE_INDEX_REQUIRED_FIELDS,
    MESSAGE_SEARCH_INDEX_PREFIX,
} from '../../search/message-index.contract';
import {
    IndexScanCursor,
    MessageSearchIndexRepository,
} from '../repository/message-search-index.repository';
import { MessageSearchTombstoneRepository } from '../repository/message-search-tombstone.repository';
import { MessageSearchIndexStateRepository } from '../repository/message-search-index-state.repository';
import { buildMessageIndexDoc } from './message-index-scope';

const SCAN_BATCH_SIZE = 500;
/** alias 전환 전에 실시간 변경을 따라잡는 최대 반복 횟수. */
const CATCH_UP_ROUNDS = 5;
/** 이 건수 이하로 줄어들면 따라잡기가 수렴한 것으로 본다. */
const CATCH_UP_CONVERGED_THRESHOLD = 10;
/** MongoDB와 색인 사이의 시계 오차를 흡수하는 catch-up 기준점 여유. */
const CATCH_UP_SKEW_MS = 10_000;
/** catch-up 한 회차가 반영에 쓸 수 있는 최대 시간. 쓰기가 스캔보다 빨라 한 회차가 끝나지 않는 경우를 대비한다. */
const CATCH_UP_ROUND_BUDGET_MS = 60_000;
const VERIFY_SAMPLE_SIZE = 20;
/** 실시간 생성·삭제로 생기는 문서 수 차이 허용치. */
const VERIFY_MISSING_TOLERANCE_RATIO = 0.001;
const VERIFY_MISSING_TOLERANCE_MINIMUM = 10;
/** 재구축 락이 이 시간보다 오래됐으면 락을 쥔 프로세스가 죽은 것으로 보고 다시 점유할 수 있다. */
const REBUILD_LOCK_STALE_THRESHOLD_MS = 30 * 60 * 1_000;

export interface RebuildOptions {
    /** 이전에 중단된 재구축을 같은 물리 index에서 이어서 진행한다. */
    resume?: boolean;
}

export interface RebuildResult {
    index: string;
    scanned: number;
    caughtUp: number;
    tombstonesReplayed: number;
    documentCount: number;
    expectedCount: number;
}

export class MessageSearchRebuildError extends Error {}

/**
 * MongoDB를 원본으로 검색 색인을 통째로 다시 만든다.
 *
 * 진행 순서는 다음과 같다.
 * 1. 새 물리 index를 만들고 색인 대상 전체를 `_id` 커서로 순회하며 bulk 색인한다.
 * 2. 스캔 시작 시각을 기준점으로 삼아, 그 이후 변경된 메시지와 삭제 tombstone을 반복 적용한다.
 * 3. 문서 수와 표본 문서의 필수 필드 존재 여부, 오류 건수를 검증한다. 실패하면 기존 alias를 그대로 둔다.
 * 4. 검증을 통과한 index로 alias를 원자적으로 전환한다.
 * 5. 전환 직후 한 번 더 따라잡아 전환 직전 창에서 발생한 변경을 메운다.
 *
 * 모든 쓰기는 메시지별 단조 증가 버전을 외부 버전으로 사용하므로, 스캔이 읽은 오래된 문서가
 * 재구축 중 발생한 최신 변경을 덮어쓰지 않는다.
 */
@Injectable()
export class MessageSearchRebuilder {
    private readonly logger = new Logger(MessageSearchRebuilder.name);

    constructor(
        private readonly elasticsearchService: ElasticsearchService,
        private readonly indexRepository: MessageSearchIndexRepository,
        private readonly tombstoneRepository: MessageSearchTombstoneRepository,
        private readonly stateRepository: MessageSearchIndexStateRepository,
    ) {}

    /**
     * 실행 전에 replica 간 락을 점유한다.
     *
     * 새 재구축은 시작하자마자 alias가 가리키지 않는 관리 index를 전부 지우므로, 두 운영자가(또는
     * 실수로 두 번) 동시에 실행하면 뒤에 시작한 쪽이 앞선 재구축의 대상 index를 삭제해 버릴 수
     * 있다. CLI 전용 명령이라 빈도는 낮지만, 이미 있는 `message_search_index_state` 단일
     * 도큐먼트에 실행 중 표시와 만료 시각을 두어 값싸게 막는다.
     */
    async rebuild(options: RebuildOptions = {}): Promise<RebuildResult> {
        const lockId = randomUUID();
        if (!await this.stateRepository.tryAcquireRebuildLock(lockId, REBUILD_LOCK_STALE_THRESHOLD_MS)) {
            throw new MessageSearchRebuildError('another search index rebuild is already running');
        }
        try {
            return await this.rebuildLocked(options);
        } finally {
            await this.stateRepository.releaseRebuildLock(lockId);
        }
    }

    private async rebuildLocked(options: RebuildOptions): Promise<RebuildResult> {
        const startedAt = new Date();
        const { index, cursor } = await this.resolveTargetIndex(options.resume === true);
        await this.stateRepository.markRebuildStarted(index, startedAt, cursor?.toString() ?? null);

        try {
            const scanned = await this.scanAll(index, cursor);
            const catchUp = await this.catchUp(index, new Date(startedAt.getTime() - CATCH_UP_SKEW_MS), CATCH_UP_ROUNDS);
            const verification = await this.verify(index);

            await this.elasticsearchService.promoteIndex(index);
            this.logger.log(`Search alias promoted to ${index}`);

            const postSwap = await this.catchUp(index, catchUp.watermark, 1);
            await this.stateRepository.markRebuildSucceeded(index, verification.documentCount);
            await this.dropReplacedIndices(index);

            return {
                index,
                scanned,
                caughtUp: catchUp.applied + postSwap.applied,
                tombstonesReplayed: catchUp.tombstones + postSwap.tombstones,
                documentCount: verification.documentCount,
                expectedCount: verification.expectedCount,
            };
        } catch (error) {
            await this.stateRepository.markRebuildFailed(errorMessageOf(error));
            this.logger.error(`Search index rebuild failed index=${index}. The existing search alias is unchanged.`, error);
            throw error;
        }
    }

    /**
     * 재구축 대상 물리 index를 정한다.
     *
     * 재개 요청이면 중단된 index와 스캔 커서를 그대로 이어 받고, 새로 시작하면 alias가 사용하지
     * 않는 이전 재구축 index를 정리한 뒤 새 index를 만든다.
     *
     * 재개 전에 그 index가 Elasticsearch에 실제로 존재하는지 확인한다. 실패한 재구축 뒤 운영자가
     * 지웠거나 다른 replica의 {@link dropReplacedIndices}가 정리했다면, 존재하지 않는 index에
     * 이어서 쓰는 순간 매핑 없는 index가 auto-create되어 `verify`가 잡아내지 못하는 상태로
     * alias에 승격될 수 있다.
     */
    private async resolveTargetIndex(resume: boolean): Promise<{ index: string; cursor: Types.ObjectId | null }> {
        if (resume) {
            const state = await this.stateRepository.get();
            const previous = state?.lastRebuildIndex;
            if (
                previous
                && isManagedMessageIndex(previous)
                && !(await this.isAliasTarget(previous))
                && await this.elasticsearchService.indexExists(previous)
            ) {
                this.logger.log(`Resuming search index rebuild index=${previous} cursor=${state?.lastRebuildScanCursor ?? 'start'}`);
                return {
                    index: previous,
                    cursor: state?.lastRebuildScanCursor ? new Types.ObjectId(state.lastRebuildScanCursor) : null,
                };
            }
            this.logger.warn('No resumable search index rebuild found, starting a new one');
        }

        await this.dropReplacedIndices(null);
        const index = buildMessageIndexName(new Date());
        await this.elasticsearchService.createIndex(index);
        this.logger.log(`Search index created for rebuild: ${index}`);
        return { index, cursor: null };
    }

    private async isAliasTarget(index: string): Promise<boolean> {
        return (await this.elasticsearchService.getAliasTargets()).includes(index);
    }

    /** alias가 쓰지 않는 재구축 index를 지운다. `keep`과 현재 alias 대상은 남긴다. */
    private async dropReplacedIndices(keep: string | null): Promise<void> {
        const [managed, aliasTargets] = await Promise.all([
            this.elasticsearchService.listManagedIndices(MESSAGE_SEARCH_INDEX_PREFIX),
            this.elasticsearchService.getAliasTargets(),
        ]);
        for (const index of managed) {
            if (index === keep || aliasTargets.includes(index) || !isManagedMessageIndex(index)) continue;
            await this.elasticsearchService.deleteIndex(index);
            this.logger.log(`Removed unused search index: ${index}`);
        }
    }

    /** 색인 대상 전체를 `_id` 커서로 순회하며 bulk 색인한다. */
    private async scanAll(index: string, startCursor: Types.ObjectId | null): Promise<number> {
        let cursor = startCursor;
        let scanned = 0;
        for (;;) {
            const batch = await this.indexRepository.scanIndexScope(cursor, SCAN_BATCH_SIZE);
            if (batch.length === 0) break;

            const result = await this.elasticsearchService.bulkUpsertMessages(
                batch.map((message) => ({
                    doc: buildMessageIndexDoc(message),
                    version: Math.max(message.searchIndexVersion, 1),
                })),
                index,
            );
            this.assertNoFailures(result.failures);

            scanned += batch.length;
            cursor = batch[batch.length - 1]._id;
            await this.stateRepository.saveRebuildScanCursor(cursor.toString());
            this.logger.log(`Search index rebuild scanned=${scanned} index=${index}`);
        }
        return scanned;
    }

    /**
     * 기준점 이후 변경과 삭제를 반복 적용해 실시간 트래픽을 따라잡는다.
     *
     * 각 회차는 시작 시각을 먼저 고정한 뒤 이전 기준점 이후의 변경을 적용하고, 그 시작 시각을
     * 다음 기준점으로 삼는다. 따라서 회차 도중 발생한 변경은 다음 회차가 반드시 다시 본다.
     *
     * 반영이 {@link CATCH_UP_ROUND_BUDGET_MS} 안에 그 창을 다 비우지 못하면(`completed: false`)
     * 기준점을 이 회차의 시작 시각으로 전진시키지 않는다. 전진시키면 아직 반영하지 못한 구간을
     * 건너뛰게 되므로, 같은 기준점으로 다음 회차를 다시 시도한다.
     */
    private async catchUp(index: string, since: Date, rounds: number): Promise<{ applied: number; tombstones: number; watermark: Date }> {
        let watermark = since;
        let applied = 0;
        let tombstones = 0;

        for (let round = 0; round < rounds; round += 1) {
            const roundStartedAt = new Date(Date.now() - CATCH_UP_SKEW_MS);
            const tombstoneResult = await this.replayTombstones(index, watermark);
            const reindexResult = await this.reindexUpdatedSince(index, watermark);
            applied += reindexResult.applied;
            tombstones += tombstoneResult.replayed;

            if (!tombstoneResult.completed || !reindexResult.completed) {
                this.logger.warn(`Search index rebuild catch-up round=${round + 1} did not drain within its time budget, retrying the same window`);
                continue;
            }

            watermark = roundStartedAt;
            if (reindexResult.applied + tombstoneResult.replayed <= CATCH_UP_CONVERGED_THRESHOLD) break;
            this.logger.log(`Search index rebuild catch-up round=${round + 1} reindexed=${reindexResult.applied} deleted=${tombstoneResult.replayed}`);
        }
        return { applied, tombstones, watermark };
    }

    private async reindexUpdatedSince(index: string, since: Date): Promise<{ applied: number; completed: boolean }> {
        const deadline = Date.now() + CATCH_UP_ROUND_BUDGET_MS;
        let cursor: IndexScanCursor | null = null;
        let applied = 0;
        for (;;) {
            if (Date.now() >= deadline) return { applied, completed: false };
            const batch = await this.indexRepository.scanUpdatedSince(since, cursor, SCAN_BATCH_SIZE);
            if (batch.length === 0) return { applied, completed: true };

            const result = await this.elasticsearchService.bulkUpsertMessages(
                batch.map((message) => ({
                    doc: buildMessageIndexDoc(message),
                    version: Math.max(message.searchIndexVersion, 1),
                })),
                index,
            );
            this.assertNoFailures(result.failures);

            applied += batch.length;
            const last = batch[batch.length - 1];
            cursor = { updatedAt: last.updatedAt, id: last._id };
        }
    }

    /**
     * 기준점 이후 기록된 삭제 tombstone을 새 index에 재생한다.
     *
     * MongoDB에 없는 문서가 색인에만 남지 않도록, 스캔이 이미 지나간 뒤 삭제된 메시지를 제거한다.
     */
    private async replayTombstones(index: string, since: Date): Promise<{ replayed: number; completed: boolean }> {
        const deadline = Date.now() + CATCH_UP_ROUND_BUDGET_MS;
        let cursor: IndexScanCursor | null = null;
        let replayed = 0;
        for (;;) {
            if (Date.now() >= deadline) return { replayed, completed: false };
            const batch = await this.tombstoneRepository.scanUpdatedSince(since, cursor, SCAN_BATCH_SIZE);
            if (batch.length === 0) return { replayed, completed: true };

            for (const tombstone of batch) {
                const result = await this.elasticsearchService.deleteMessage(tombstone.messageId, tombstone.version, index);
                if (result.outcome === 'RETRYABLE' || result.outcome === 'PERMANENT') {
                    throw new MessageSearchRebuildError(
                        `tombstone replay failed messageId=${tombstone.messageId}: ${result.error}`,
                    );
                }
            }
            replayed += batch.length;
            const last = batch[batch.length - 1];
            cursor = { updatedAt: last.updatedAt, id: last._id };
        }
    }

    /**
     * 새 index를 alias에 붙이기 전에 검증한다.
     *
     * 문서 수가 MongoDB 기준보다 크게 모자라거나 표본 문서에 필수 필드가 빠져 있으면 전환하지
     * 않는다. 검증되지 않은 index는 검색 트래픽에 노출되지 않는다.
     */
    private async verify(index: string): Promise<{ documentCount: number; expectedCount: number }> {
        await this.elasticsearchService.refreshIndex(index);
        const [documentCount, expectedCount] = await Promise.all([
            this.elasticsearchService.countDocuments(index),
            this.indexRepository.countIndexScope(),
        ]);

        const tolerance = Math.max(VERIFY_MISSING_TOLERANCE_MINIMUM, Math.ceil(expectedCount * VERIFY_MISSING_TOLERANCE_RATIO));
        if (documentCount < expectedCount - tolerance) {
            throw new MessageSearchRebuildError(
                `rebuilt index is missing documents: indexed=${documentCount} expected=${expectedCount} tolerance=${tolerance}`,
            );
        }

        const samples = await this.elasticsearchService.sampleDocuments(index, VERIFY_SAMPLE_SIZE);
        for (const sample of samples) {
            const missing = MESSAGE_INDEX_REQUIRED_FIELDS.filter((field) => sample[field] === undefined || sample[field] === null);
            if (missing.length > 0) {
                throw new MessageSearchRebuildError(
                    `rebuilt document is missing required fields messageId=${sample.messageId ?? 'unknown'} fields=${missing.join(',')}`,
                );
            }
        }
        this.logger.log(`Search index verified index=${index} documents=${documentCount} expected=${expectedCount}`);
        return { documentCount, expectedCount };
    }

    private assertNoFailures(failures: Array<{ messageId: string; error: string }>): void {
        if (failures.length === 0) return;
        const [first] = failures;
        throw new MessageSearchRebuildError(
            `bulk indexing failed for ${failures.length} document(s), first messageId=${first.messageId}: ${first.error}`,
        );
    }
}
