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
const VERIFY_SAMPLE_SIZE = 20;
/** 실시간 생성·삭제로 생기는 문서 수 차이 허용치. */
const VERIFY_MISSING_TOLERANCE_RATIO = 0.001;
const VERIFY_MISSING_TOLERANCE_MINIMUM = 10;

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
 * 3. 문서 수·필수 필드·표본 내용·오류 건수를 검증한다. 실패하면 기존 alias를 그대로 둔다.
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

    async rebuild(options: RebuildOptions = {}): Promise<RebuildResult> {
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
     */
    private async resolveTargetIndex(resume: boolean): Promise<{ index: string; cursor: Types.ObjectId | null }> {
        if (resume) {
            const state = await this.stateRepository.get();
            const previous = state?.lastRebuildIndex;
            if (previous && isManagedMessageIndex(previous) && !(await this.isAliasTarget(previous))) {
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
     */
    private async catchUp(index: string, since: Date, rounds: number): Promise<{ applied: number; tombstones: number; watermark: Date }> {
        let watermark = since;
        let applied = 0;
        let tombstones = 0;

        for (let round = 0; round < rounds; round += 1) {
            const roundStartedAt = new Date(Date.now() - CATCH_UP_SKEW_MS);
            const replayed = await this.replayTombstones(index, watermark);
            const reindexed = await this.reindexUpdatedSince(index, watermark);
            watermark = roundStartedAt;
            applied += reindexed;
            tombstones += replayed;

            if (reindexed + replayed <= CATCH_UP_CONVERGED_THRESHOLD) break;
            this.logger.log(`Search index rebuild catch-up round=${round + 1} reindexed=${reindexed} deleted=${replayed}`);
        }
        return { applied, tombstones, watermark };
    }

    private async reindexUpdatedSince(index: string, since: Date): Promise<number> {
        let cursor: IndexScanCursor | null = null;
        let applied = 0;
        for (;;) {
            const batch = await this.indexRepository.scanUpdatedSince(since, cursor, SCAN_BATCH_SIZE);
            if (batch.length === 0) break;

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
        return applied;
    }

    /**
     * 기준점 이후 기록된 삭제 tombstone을 새 index에 재생한다.
     *
     * MongoDB에 없는 문서가 색인에만 남지 않도록, 스캔이 이미 지나간 뒤 삭제된 메시지를 제거한다.
     */
    private async replayTombstones(index: string, since: Date): Promise<number> {
        let cursor: Types.ObjectId | null = null;
        let replayed = 0;
        for (;;) {
            const batch = await this.tombstoneRepository.scanUpdatedSince(since, cursor, SCAN_BATCH_SIZE);
            if (batch.length === 0) break;

            for (const tombstone of batch) {
                const result = await this.elasticsearchService.deleteMessage(tombstone.messageId, tombstone.version, index);
                if (result.outcome === 'RETRYABLE' || result.outcome === 'PERMANENT') {
                    throw new MessageSearchRebuildError(
                        `tombstone replay failed messageId=${tombstone.messageId}: ${result.error}`,
                    );
                }
            }
            replayed += batch.length;
            cursor = batch[batch.length - 1]._id;
        }
        return replayed;
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
