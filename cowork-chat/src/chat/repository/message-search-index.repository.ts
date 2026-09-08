import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
import { Message } from '../schema/message.schema';
import {
    isSearchIndexed,
    MessageIndexSource,
    SEARCH_INDEX_SCOPE_FILTER,
    SearchIndexStatus,
} from '../search/message-index-scope';

/** 아웃박스 워커가 점유한 메시지. 색인 문서를 만들 수 있는 최소 필드만 담는다. */
export type ClaimedIndexMessage = MessageIndexSource & {
    _id: Types.ObjectId;
    searchIndexVersion: number;
    searchIndexRetryCount: number;
};

/** 재구축 스캔이 사용하는 커서. `(updatedAt, _id)` 복합 순서로 페이지를 이어 받는다. */
export interface IndexScanCursor {
    updatedAt: Date;
    id: Types.ObjectId;
}

export interface SearchIndexBacklog {
    counts: Record<SearchIndexStatus, number>;
    oldestPendingAt: Date | null;
}

const INDEX_SOURCE_PROJECTION = {
    teamId: 1, projectId: 1, channelId: 1, authorId: 1, content: 1, type: 1,
    attachments: 1, isPinned: 1, createdAt: 1, updatedAt: 1,
    searchIndexVersion: 1, searchIndexRetryCount: 1,
} as const;

/** 아웃박스 상태 전이는 실제 내용 변경이 아니므로 `updatedAt`을 건드리지 않는다. */
const OUTBOX_UPDATE_OPTIONS = { timestamps: false } as const;

/**
 * 메시지 검색 색인 아웃박스 저장소.
 *
 * 메시지 도큐먼트 자체가 아웃박스이므로 MongoDB 변경과 색인 의도가 같은 쓰기에 남는다.
 * 이 저장소는 그 상태를 점유·완료·재시도로 전이시키고, 전체 재구축을 위한 커서 스캔을 제공한다.
 */
@Injectable()
export class MessageSearchIndexRepository {
    constructor(@InjectModel(Message.name) private readonly messageModel: Model<Message>) {}

    /**
     * 색인 대기 메시지를 최대 `batchSize`개 원자적으로 점유한다.
     *
     * 여러 replica가 동시에 호출해도 `PENDING`을 만족하는 문서만 전이되므로 중복 점유가 없다.
     * 되읽기 조건에는 점유 시각이 아니라 호출마다 새로 발급한 `searchIndexClaimId`를 쓴다.
     * 두 워커가 같은 밀리초에 점유하면 시각만으로는 서로의 점유분을 구분할 수 없기 때문이다.
     */
    async claimPending(batchSize: number): Promise<ClaimedIndexMessage[]> {
        const now = new Date();
        const claimId = new Types.ObjectId().toString();
        const candidates = await this.messageModel
            .find({ searchIndexStatus: 'PENDING', searchIndexNextAttemptAt: { $lte: now } })
            .sort({ searchIndexNextAttemptAt: 1 })
            .limit(batchSize)
            .select('_id')
            .lean();
        if (candidates.length === 0) return [];

        const ids = candidates.map((candidate) => candidate._id);
        await this.messageModel.updateMany(
            { _id: { $in: ids }, searchIndexStatus: 'PENDING' },
            { $set: { searchIndexStatus: 'PROCESSING', searchIndexProcessingStartedAt: now, searchIndexClaimId: claimId } },
            OUTBOX_UPDATE_OPTIONS,
        );

        return this.messageModel
            .find({ _id: { $in: ids }, searchIndexStatus: 'PROCESSING', searchIndexClaimId: claimId })
            .select(INDEX_SOURCE_PROJECTION)
            .lean<ClaimedIndexMessage[]>();
    }

    /**
     * 점유한 버전이 색인에 반영되었음을 기록한다.
     *
     * 점유 이후 메시지가 다시 변경되었으면 `searchIndexVersion`이 달라 이 갱신은 적용되지 않고,
     * 새로 기록된 `PENDING` 의도가 그대로 살아남는다.
     */
    async markSynced(id: Types.ObjectId, version: number): Promise<void> {
        await this.messageModel.updateOne(
            { _id: id, searchIndexStatus: 'PROCESSING', searchIndexVersion: version },
            {
                $set: {
                    searchIndexStatus: 'SYNCED',
                    searchIndexSyncedVersion: version,
                    searchIndexSyncedAt: new Date(),
                    searchIndexRetryCount: 0,
                    searchIndexLastError: null,
                    searchIndexProcessingStartedAt: null,
                    searchIndexClaimId: null,
                    searchIndexNextAttemptAt: null,
                },
            },
            OUTBOX_UPDATE_OPTIONS,
        );
    }

    /**
     * 점유했지만 색인 대상이 아닌 것으로 판정된 문서를 `SKIPPED`로 확정한다.
     *
     * `releaseDeleting`처럼 대상 여부를 다시 판정하지 않는 경로에서 `PENDING`으로 되돌려진
     * 문서가 다시 점유됐을 때의 방어선이다. `markSynced`와 같은 `PROCESSING` + 버전 가드를 쓴다.
     */
    async markSkipped(id: Types.ObjectId, version: number): Promise<void> {
        await this.messageModel.updateOne(
            { _id: id, searchIndexStatus: 'PROCESSING', searchIndexVersion: version },
            {
                $set: {
                    searchIndexStatus: 'SKIPPED',
                    searchIndexVersion: 0,
                    searchIndexRetryCount: 0,
                    searchIndexLastError: null,
                    searchIndexProcessingStartedAt: null,
                    searchIndexClaimId: null,
                    searchIndexNextAttemptAt: null,
                },
            },
            OUTBOX_UPDATE_OPTIONS,
        );
    }

    /** 재시도 가능 실패를 기록하고 백오프 후 다시 대기 상태로 되돌린다. */
    async markRetry(id: Types.ObjectId, version: number, retryCount: number, nextAttemptAt: Date, error: string): Promise<void> {
        await this.messageModel.updateOne(
            { _id: id, searchIndexStatus: 'PROCESSING', searchIndexVersion: version },
            {
                $set: {
                    searchIndexStatus: 'PENDING',
                    searchIndexRetryCount: retryCount,
                    searchIndexNextAttemptAt: nextAttemptAt,
                    searchIndexProcessingStartedAt: null,
                    searchIndexClaimId: null,
                    searchIndexLastError: error,
                },
            },
            OUTBOX_UPDATE_OPTIONS,
        );
    }

    /** 영구 실패를 기록한다. 운영자가 원인을 해소한 뒤 재시도를 지시해야 한다. */
    async markFailed(id: Types.ObjectId, version: number, error: string): Promise<void> {
        await this.messageModel.updateOne(
            { _id: id, searchIndexStatus: 'PROCESSING', searchIndexVersion: version },
            {
                $set: {
                    searchIndexStatus: 'FAILED',
                    searchIndexNextAttemptAt: null,
                    searchIndexProcessingStartedAt: null,
                    searchIndexClaimId: null,
                    searchIndexLastError: error,
                },
            },
            OUTBOX_UPDATE_OPTIONS,
        );
    }

    /** 워커가 중단되어 `PROCESSING`에 머문 항목을 다시 대기 상태로 회수한다. */
    async reclaimStaleProcessing(staleThresholdMs: number): Promise<number> {
        const result = await this.messageModel.updateMany(
            {
                searchIndexStatus: 'PROCESSING',
                searchIndexProcessingStartedAt: { $lt: new Date(Date.now() - staleThresholdMs) },
            },
            {
                $set: {
                    searchIndexStatus: 'PENDING',
                    searchIndexNextAttemptAt: new Date(),
                    searchIndexProcessingStartedAt: null,
                    searchIndexClaimId: null,
                    searchIndexLastError: 'search index claim timed out',
                },
            },
            OUTBOX_UPDATE_OPTIONS,
        );
        return result.modifiedCount;
    }

    /**
     * tombstone을 남기기 전에 중단되어 `DELETING`에 머문 메시지를 조회한다.
     *
     * 메시지가 아직 MongoDB에 있으면 삭제는 커밋되지 않은 것이므로, tombstone이 없는 항목만
     * {@link releaseDeleting}으로 되돌려 다시 색인 대상이 되게 한다. `reserveDeletion`은 색인
     * 대상 여부와 무관하게 모든 메시지를 `DELETING`으로 고정하므로, 되돌릴 때 대상 여부를
     * 다시 판정할 수 있도록 `teamId`/`projectId`/`type`도 함께 반환한다.
     */
    async findStaleDeleting(
        staleThresholdMs: number,
        limit: number,
    ): Promise<Array<{ _id: Types.ObjectId; teamId: number | null; projectId: number | null; type: string }>> {
        return this.messageModel
            .find({
                searchIndexStatus: 'DELETING',
                searchIndexProcessingStartedAt: { $lt: new Date(Date.now() - staleThresholdMs) },
            })
            .limit(limit)
            .select({ teamId: 1, projectId: 1, type: 1 })
            .lean();
    }

    /**
     * `DELETING`에서 되돌릴 메시지를 색인 대상 여부에 따라 갈라 확정한다.
     *
     * 색인 대상이면 `PENDING`으로 되돌려 다시 색인하게 하고, 대상이 아니면(DM·`SYSTEM` 등)
     * `SKIPPED`(+`searchIndexVersion: 0`)로 확정해 대상이 아닌 메시지가 색인에 적재되지 않게 한다.
     */
    async releaseDeleting(
        messages: Array<{ _id: Types.ObjectId; teamId: number | null; projectId: number | null; type: string }>,
    ): Promise<number> {
        if (messages.length === 0) return 0;
        const indexedIds = messages.filter((message) => isSearchIndexed(message)).map((message) => message._id);
        const skippedIds = messages.filter((message) => !isSearchIndexed(message)).map((message) => message._id);

        let modifiedCount = 0;
        if (indexedIds.length > 0) {
            const result = await this.messageModel.updateMany(
                { _id: { $in: indexedIds }, searchIndexStatus: 'DELETING' },
                {
                    $set: {
                        searchIndexStatus: 'PENDING',
                        searchIndexNextAttemptAt: new Date(),
                        searchIndexProcessingStartedAt: null,
                        searchIndexLastError: 'deletion was not committed, re-indexing',
                    },
                },
                OUTBOX_UPDATE_OPTIONS,
            );
            modifiedCount += result.modifiedCount;
        }
        if (skippedIds.length > 0) {
            const result = await this.messageModel.updateMany(
                { _id: { $in: skippedIds }, searchIndexStatus: 'DELETING' },
                {
                    $set: {
                        searchIndexStatus: 'SKIPPED',
                        searchIndexVersion: 0,
                        searchIndexNextAttemptAt: null,
                        searchIndexProcessingStartedAt: null,
                        searchIndexLastError: null,
                    },
                },
                OUTBOX_UPDATE_OPTIONS,
            );
            modifiedCount += result.modifiedCount;
        }
        return modifiedCount;
    }

    /**
     * 아웃박스 필드가 없는 레거시 메시지에 초기 상태를 채운다.
     *
     * 색인 대상이면 버전 `1`과 함께 `PENDING`으로 두어 워커가 색인을 복원하게 하고,
     * 대상이 아니면 `SKIPPED`로 확정해 이후 스캔에서 제외한다.
     *
     * @returns 이번 호출에서 상태를 채운 도큐먼트 수. `0`이면 백필이 끝난 것이다
     */
    async backfillLegacyState(batchSize: number): Promise<number> {
        const legacy = await this.messageModel
            .find({ searchIndexStatus: null })
            .limit(batchSize)
            .select({ teamId: 1, projectId: 1, type: 1 })
            .lean();
        if (legacy.length === 0) return 0;

        const indexedIds = legacy.filter((message) => isSearchIndexed(message)).map((message) => message._id);
        const skippedIds = legacy.filter((message) => !isSearchIndexed(message)).map((message) => message._id);

        if (indexedIds.length > 0) {
            await this.messageModel.updateMany(
                { _id: { $in: indexedIds }, searchIndexStatus: null },
                {
                    $set: {
                        searchIndexStatus: 'PENDING',
                        searchIndexVersion: 1,
                        searchIndexSyncedVersion: 0,
                        searchIndexRetryCount: 0,
                        searchIndexNextAttemptAt: new Date(),
                        searchIndexProcessingStartedAt: null,
                        searchIndexLastError: null,
                        searchIndexSyncedAt: null,
                    },
                },
                OUTBOX_UPDATE_OPTIONS,
            );
        }
        if (skippedIds.length > 0) {
            await this.messageModel.updateMany(
                { _id: { $in: skippedIds }, searchIndexStatus: null },
                { $set: { searchIndexStatus: 'SKIPPED', searchIndexVersion: 0 } },
                OUTBOX_UPDATE_OPTIONS,
            );
        }
        return legacy.length;
    }

    /** 영구 실패 항목을 다시 대기 상태로 되돌린다. 운영자 재시도 명령이 사용한다. */
    async retryFailed(): Promise<number> {
        const result = await this.messageModel.updateMany(
            { searchIndexStatus: 'FAILED' },
            {
                $set: {
                    searchIndexStatus: 'PENDING',
                    searchIndexRetryCount: 0,
                    searchIndexNextAttemptAt: new Date(),
                    searchIndexProcessingStartedAt: null,
                },
            },
            OUTBOX_UPDATE_OPTIONS,
        );
        return result.modifiedCount;
    }

    /** 상태별 backlog와 가장 오래된 대기 항목의 시각을 조회한다. */
    /**
     * 상태별 backlog를 집계한다.
     *
     * `SYNCED`/`SKIPPED`는 대부분을 차지하는 종결 상태라 매번 전체를 훑을 필요가 없다.
     * `searchIndexStatus` 인덱스를 타도록 진행 중인 상태로 먼저 `$match`를 걸어, 30초마다 도는
     * 이 조회가 컬렉션이 커져도 COLLSCAN이 되지 않게 한다. 그 대가로 `SYNCED`/`SKIPPED` 카운트는
     * 갱신되지 않고 `0`으로 유지되지만, 이 지표의 목적인 backlog·지연 감시에는 필요 없는 값이다.
     */
    async backlog(): Promise<SearchIndexBacklog> {
        const rows = await this.messageModel.aggregate<{ _id: SearchIndexStatus | null; count: number; oldest: Date | null }>([
            { $match: { searchIndexStatus: { $in: ['PENDING', 'PROCESSING', 'FAILED', 'DELETING'] } } },
            { $group: { _id: '$searchIndexStatus', count: { $sum: 1 }, oldest: { $min: '$searchIndexNextAttemptAt' } } },
        ]);
        const counts = { PENDING: 0, PROCESSING: 0, SYNCED: 0, FAILED: 0, DELETING: 0, SKIPPED: 0 } as Record<SearchIndexStatus, number>;
        let oldestPendingAt: Date | null = null;
        for (const row of rows) {
            if (row._id === null) continue;
            counts[row._id] = row.count;
            if (row._id === 'PENDING') oldestPendingAt = row.oldest;
        }
        return { counts, oldestPendingAt };
    }

    countIndexScope(): Promise<number> {
        return this.messageModel.countDocuments(SEARCH_INDEX_SCOPE_FILTER);
    }

    /** 색인 대상 전체를 `_id` 오름차순 커서로 순회한다. 전체 재구축의 snapshot scan이다. */
    scanIndexScope(afterId: Types.ObjectId | null, batchSize: number): Promise<ClaimedIndexMessage[]> {
        return this.messageModel
            .find(afterId ? { ...SEARCH_INDEX_SCOPE_FILTER, _id: { $gt: afterId } } : SEARCH_INDEX_SCOPE_FILTER)
            .sort({ _id: 1 })
            .limit(batchSize)
            .select(INDEX_SOURCE_PROJECTION)
            .lean<ClaimedIndexMessage[]>();
    }

    /**
     * snapshot 기준점 이후에 변경된 색인 대상 메시지를 순회한다.
     *
     * 전체 재구축이 스캔을 마친 뒤 실시간 변경을 따라잡는 catch-up 단계에서 사용한다.
     */
    scanUpdatedSince(since: Date, cursor: IndexScanCursor | null, batchSize: number): Promise<Array<ClaimedIndexMessage & { updatedAt: Date }>> {
        const filter = cursor
            ? {
                ...SEARCH_INDEX_SCOPE_FILTER,
                $or: [
                    { updatedAt: { $gt: cursor.updatedAt } },
                    { updatedAt: cursor.updatedAt, _id: { $gt: cursor.id } },
                ],
                updatedAt: { $gte: since },
            }
            : { ...SEARCH_INDEX_SCOPE_FILTER, updatedAt: { $gte: since } };
        return this.messageModel
            .find(filter)
            .sort({ updatedAt: 1, _id: 1 })
            .limit(batchSize)
            .select(INDEX_SOURCE_PROJECTION)
            .lean<Array<ClaimedIndexMessage & { updatedAt: Date }>>();
    }
}
