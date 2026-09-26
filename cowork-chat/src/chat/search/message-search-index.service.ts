import { Injectable, Logger } from '@nestjs/common';
import { DicoshotService } from 'dicoshot-nest';
import { Counter, Gauge, register } from 'prom-client';
import { ElasticsearchService } from '../../search/elasticsearch.service';
import { errorMessageOf, IndexWriteResult } from '../../search/message-index.contract';
import { MessageRepository } from '../repository/message.repository';
import {
    ClaimedIndexMessage,
    MessageSearchIndexRepository,
} from '../repository/message-search-index.repository';
import {
    MessageSearchTombstoneRepository,
    TombstoneRecord,
} from '../repository/message-search-tombstone.repository';
import { MessageSearchIndexStateRepository } from '../repository/message-search-index-state.repository';
import { AlertThrottleUtil } from '../../common/util/alert-throttle.util';
import { buildMessageIndexDoc, isSearchIndexed } from './message-index-scope';

/** 재시도 백오프의 기준 간격. 시도마다 2배로 늘어난다. */
const RETRY_BASE_DELAY_MS = 5_000;
/** 재시도 백오프 상한. Elasticsearch가 장기간 중단돼도 이 간격으로 계속 재시도한다. */
const RETRY_MAX_DELAY_MS = 5 * 60 * 1_000;
const PERMANENT_FAILURE_ALERT_COOLDOWN_MS = 5 * 60 * 1_000;

export type SearchIndexOperation = 'upsert' | 'delete';

/**
 * 색인 아웃박스 항목 하나를 Elasticsearch에 적용하고 결과에 따라 상태를 전이시킨다.
 *
 * 재시도 가능 오류는 횟수 제한 없이 상한이 있는 백오프로 계속 재시도한다. 일시적인
 * Elasticsearch 장애가 해소되면 수동 개입 없이 색인이 MongoDB 최종 상태로 수렴해야 하므로,
 * 영구 실패(`FAILED`)는 문서·매핑 계약 위반처럼 재시도가 의미 없는 경우로만 한정한다.
 */
@Injectable()
export class MessageSearchIndexService {
    private readonly logger = new Logger(MessageSearchIndexService.name);
    private readonly writes: Counter<'operation' | 'outcome'>;
    private readonly backlog: Gauge<'status'>;
    private readonly tombstoneBacklog: Gauge<'status'>;
    private readonly oldestPendingSeconds: Gauge<string>;
    private readonly lastRebuildSuccess: Gauge<string>;
    private readonly indexReady: Gauge<string>;

    constructor(
        private readonly elasticsearchService: ElasticsearchService,
        private readonly messageRepository: MessageRepository,
        private readonly indexRepository: MessageSearchIndexRepository,
        private readonly tombstoneRepository: MessageSearchTombstoneRepository,
        private readonly stateRepository: MessageSearchIndexStateRepository,
        private readonly dicoshot: DicoshotService,
    ) {
        this.writes = counter('cowork_chat_search_index_write_total', 'Search index write attempts by outcome.', ['operation', 'outcome']);
        this.backlog = gauge('cowork_chat_search_index_backlog', 'Messages per search index outbox status.', ['status']);
        this.tombstoneBacklog = gauge('cowork_chat_search_index_tombstone_backlog', 'Search index delete tombstones per status.', ['status']);
        this.oldestPendingSeconds = gauge('cowork_chat_search_index_oldest_pending_seconds', 'Age of the oldest message waiting for search indexing.', []);
        this.lastRebuildSuccess = gauge('cowork_chat_search_index_last_rebuild_success_timestamp_seconds', 'Unix time of the last successful search index rebuild.', []);
        this.indexReady = gauge('cowork_chat_search_index_ready', 'Whether the search index alias is usable (1) or not (0).', []);
    }

    /**
     * 점유한 메시지의 최신 전체 문서를 색인에 반영한다.
     *
     * `claimPending`은 `searchIndexStatus`만 보고 점유하므로, `releaseDeleting`처럼 대상 여부를
     * 다시 판정하지 않는 경로에서 `PENDING`이 된 비대상 문서가 여기로 들어올 수 있다.
     * {@link buildMessageIndexDoc}는 호출 전 대상 확인을 전제하므로, 이 방어가 없으면 `teamId`
     * 단정(`!`)이 깨진 문서가 그대로 색인될 수 있다.
     */
    async applyMessage(message: ClaimedIndexMessage): Promise<void> {
        await this.applyMessages([message]);
    }

    /**
     * {@link applyMessage}의 배치 버전. Elasticsearch에 문서 수만큼 개별 요청을 보내는 대신
     * `_bulk` API 한 번으로 묶어 보낸다. 메시지별 상태 전이(`markSynced`/`markRetry`/`markFailed`)는
     * 배치 안에서도 서로 독립적이므로 결과를 순회하며 그대로 적용한다.
     */
    async applyMessages(messages: ClaimedIndexMessage[]): Promise<void> {
        if (messages.length === 0) return;
        const toSkip = messages.filter((message) => !isSearchIndexed(message));
        const toIndex = messages.filter((message) => isSearchIndexed(message));

        await Promise.all(toSkip.map((message) => this.indexRepository.markSkipped(message._id, message.searchIndexVersion)));
        if (toIndex.length === 0) return;

        const entries = toIndex.map((message) => ({ doc: buildMessageIndexDoc(message), version: message.searchIndexVersion }));
        const results = await this.elasticsearchService.bulkUpsertForOutbox(entries);
        const byMessageId = new Map(toIndex.map((message) => [message._id.toString(), message]));

        await Promise.all(results.map(({ messageId, result }) => {
            this.writes.inc({ operation: 'upsert', outcome: result.outcome });
            const message = byMessageId.get(messageId);
            return message ? this.finalizeUpsertResult(message, result) : undefined;
        }));
    }

    private async finalizeUpsertResult(message: ClaimedIndexMessage, result: IndexWriteResult): Promise<void> {
        const version = message.searchIndexVersion;
        if (result.outcome === 'APPLIED' || result.outcome === 'SUPERSEDED') {
            await this.indexRepository.markSynced(message._id, version);
            return;
        }
        if (result.outcome === 'PERMANENT') {
            await this.indexRepository.markFailed(message._id, version, result.error ?? 'permanent index error');
            this.alertPermanentFailure('upsert', message._id.toString(), result.error);
            return;
        }
        const retryCount = (message.searchIndexRetryCount ?? 0) + 1;
        await this.indexRepository.markRetry(
            message._id,
            version,
            retryCount,
            this.nextAttemptAt(retryCount),
            result.error ?? 'retryable index error',
        );
    }

    /**
     * 삭제 tombstone을 적용한다.
     *
     * tombstone 기록 뒤 MongoDB 삭제 전에 중단됐을 수 있으므로 메시지 삭제를 먼저 완결한 다음
     * 색인 문서를 제거한다. 두 단계 모두 멱등하다.
     */
    async applyTombstone(tombstone: TombstoneRecord): Promise<void> {
        await this.applyTombstones([tombstone]);
    }

    /**
     * {@link applyTombstone}의 배치 버전. 각 tombstone의 MongoDB 삭제를 전부 완결한 뒤에야
     * Elasticsearch 삭제를 `_bulk`로 묶어 보내, "메시지 삭제 먼저" 순서를 tombstone별로 유지한다
     * (서로 다른 tombstone 사이의 순서는 상관없다).
     *
     * MongoDB 삭제는 `Promise.allSettled`로 모아 한 tombstone의 실패가 나머지의 진행을 막지
     * 않게 한다 — `Promise.all`이었다면 하나만 reject해도 배치 전체가 `PROCESSING`에 남아
     * ES 삭제도 상태 전이도 못 했다. 실패한 항목은 RETRYABLE로 개별 처리하고, 성공한 항목만
     * bulk 삭제 대상에 포함한다.
     */
    async applyTombstones(tombstones: TombstoneRecord[]): Promise<void> {
        if (tombstones.length === 0) return;
        const deletions = await Promise.allSettled(
            tombstones.map((tombstone) => this.messageRepository.deleteById(tombstone.messageId)),
        );
        const succeeded: TombstoneRecord[] = [];
        const failures: Array<{ tombstone: TombstoneRecord; error: unknown }> = [];
        tombstones.forEach((tombstone, i) => {
            const outcome = deletions[i];
            if (outcome.status === 'fulfilled') succeeded.push(tombstone);
            else failures.push({ tombstone, error: outcome.reason });
        });

        await Promise.all(failures.map(({ tombstone, error }) => {
            this.writes.inc({ operation: 'delete', outcome: 'RETRYABLE' });
            this.logger.warn(`MongoDB delete failed for search tombstone, will retry messageId=${tombstone.messageId}: ${errorMessageOf(error)}`);
            return this.finalizeDeleteResult(tombstone, { outcome: 'RETRYABLE', error: errorMessageOf(error) });
        }));
        if (succeeded.length === 0) return;

        const entries = succeeded.map((tombstone) => ({ messageId: tombstone.messageId, version: tombstone.version }));
        const results = await this.elasticsearchService.bulkDeleteForOutbox(entries);
        const byMessageId = new Map(succeeded.map((tombstone) => [tombstone.messageId, tombstone]));

        await Promise.all(results.map(({ messageId, result }) => {
            this.writes.inc({ operation: 'delete', outcome: result.outcome });
            const tombstone = byMessageId.get(messageId);
            return tombstone ? this.finalizeDeleteResult(tombstone, result) : undefined;
        }));
    }

    private async finalizeDeleteResult(tombstone: TombstoneRecord, result: IndexWriteResult): Promise<void> {
        if (result.outcome === 'APPLIED' || result.outcome === 'SUPERSEDED') {
            await this.tombstoneRepository.markDeleted(tombstone._id);
            return;
        }
        if (result.outcome === 'PERMANENT') {
            await this.tombstoneRepository.markFailed(tombstone._id, result.error ?? 'permanent delete error');
            this.alertPermanentFailure('delete', tombstone.messageId, result.error);
            return;
        }
        const retryCount = tombstone.retryCount + 1;
        await this.tombstoneRepository.markRetry(
            tombstone._id,
            retryCount,
            this.nextAttemptAt(retryCount),
            result.error ?? 'retryable delete error',
        );
    }

    /** 시도 횟수에 따른 지수 백오프 시각. 상한이 있어 재시도를 포기하지 않는다. */
    private nextAttemptAt(retryCount: number): Date {
        const delay = Math.min(RETRY_BASE_DELAY_MS * 2 ** Math.min(retryCount, 10), RETRY_MAX_DELAY_MS);
        return new Date(Date.now() + delay);
    }

    private alertPermanentFailure(operation: SearchIndexOperation, messageId: string, error?: string): void {
        this.logger.error(`Search index ${operation} permanently failed messageId=${messageId}: ${error}`);
        if (!AlertThrottleUtil.shouldAlert('search-index-permanent-failure', PERMANENT_FAILURE_ALERT_COOLDOWN_MS)) return;
        void this.dicoshot.sendCustom({
            title: '🔴 검색 색인 영구 실패',
            description: '메시지 색인 명령이 재시도로 해결되지 않는 오류로 실패했습니다. 원인 해소 후 재시도 또는 전체 재구축이 필요합니다.',
            color: 'danger',
            fields: [
                { name: 'Operation', value: operation, inline: true },
                { name: 'messageId', value: messageId, inline: true },
                { name: 'Error', value: (error ?? 'unknown').slice(0, 256) },
            ],
        }).catch(() => {});
    }

    /** backlog·지연·마지막 재구축 성공 시각을 지표로 노출한다. */
    async refreshMetrics(): Promise<void> {
        const [backlog, tombstones, state] = await Promise.all([
            this.indexRepository.backlog(),
            this.tombstoneRepository.countByStatus(),
            this.stateRepository.get(),
        ]);

        for (const [status, count] of Object.entries(backlog.counts)) {
            this.backlog.set({ status }, count);
        }
        for (const [status, count] of Object.entries(tombstones)) {
            this.tombstoneBacklog.set({ status }, count);
        }
        this.oldestPendingSeconds.set(
            backlog.oldestPendingAt ? Math.max(0, (Date.now() - backlog.oldestPendingAt.getTime()) / 1_000) : 0,
        );
        this.lastRebuildSuccess.set(state?.lastRebuildSucceededAt ? state.lastRebuildSucceededAt.getTime() / 1_000 : 0);
        this.indexReady.set(this.elasticsearchService.isReady() ? 1 : 0);
    }
}

function counter<L extends string>(name: string, help: string, labelNames: L[]): Counter<L> {
    return (register.getSingleMetric(name) as Counter<L> | undefined) ?? new Counter({ name, help, labelNames });
}

function gauge<L extends string>(name: string, help: string, labelNames: L[]): Gauge<L> {
    return (register.getSingleMetric(name) as Gauge<L> | undefined) ?? new Gauge({ name, help, labelNames });
}
