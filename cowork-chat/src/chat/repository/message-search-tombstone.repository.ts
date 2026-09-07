import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
import {
    MessageSearchTombstone,
    MessageSearchTombstoneStatus,
} from '../schema/message-search-tombstone.schema';

export type TombstoneRecord = MessageSearchTombstone & { _id: Types.ObjectId };

export interface CreateTombstoneInput {
    messageId: string;
    teamId: number;
    projectId: number;
    channelId: number;
    version: number;
    retentionDays: number;
}

const OUTBOX_UPDATE_OPTIONS = { timestamps: false } as const;

/**
 * 삭제 색인 명령 저장소.
 *
 * 메시지는 hard delete되므로 삭제 의도는 이 collection에만 남는다. `messageId` unique 인덱스로
 * 재시도가 멱등하며, 보존 기간 동안 남아 전체 재구축의 catch-up이 삭제를 재생할 수 있다.
 */
@Injectable()
export class MessageSearchTombstoneRepository {
    constructor(
        @InjectModel(MessageSearchTombstone.name) private readonly model: Model<MessageSearchTombstone>,
    ) {}

    /**
     * 삭제 tombstone을 멱등하게 기록한다.
     *
     * 이미 tombstone이 있으면 더 높은 버전으로만 갱신해, 재시도가 버전을 되돌리지 않게 한다.
     */
    async create(input: CreateTombstoneInput): Promise<void> {
        const now = new Date();
        await this.model.updateOne(
            { messageId: input.messageId },
            [
                {
                    $set: {
                        teamId: input.teamId,
                        projectId: input.projectId,
                        channelId: input.channelId,
                        version: { $max: [{ $ifNull: ['$version', 0] }, input.version] },
                        status: 'PENDING',
                        retryCount: 0,
                        nextAttemptAt: now,
                        processingStartedAt: null,
                        lastError: null,
                        deletedAt: null,
                        expiresAt: new Date(now.getTime() + input.retentionDays * 24 * 60 * 60 * 1_000),
                        createdAt: { $ifNull: ['$createdAt', now] },
                        updatedAt: now,
                    },
                },
            ],
            { upsert: true },
        );
    }

    /** 처리 대기 tombstone을 최대 `batchSize`개 원자적으로 점유한다. */
    async claimPending(batchSize: number): Promise<TombstoneRecord[]> {
        const now = new Date();
        const candidates = await this.model
            .find({ status: 'PENDING', nextAttemptAt: { $lte: now } })
            .sort({ nextAttemptAt: 1 })
            .limit(batchSize)
            .select('_id')
            .lean();
        if (candidates.length === 0) return [];

        const ids = candidates.map((candidate) => candidate._id);
        await this.model.updateMany(
            { _id: { $in: ids }, status: 'PENDING' },
            { $set: { status: 'PROCESSING', processingStartedAt: now } },
            OUTBOX_UPDATE_OPTIONS,
        );

        return this.model
            .find({ _id: { $in: ids }, status: 'PROCESSING', processingStartedAt: now })
            .lean<TombstoneRecord[]>();
    }

    async markDeleted(id: Types.ObjectId): Promise<void> {
        await this.model.updateOne(
            { _id: id, status: 'PROCESSING' },
            { $set: { status: 'DELETED', deletedAt: new Date(), lastError: null, processingStartedAt: null } },
            OUTBOX_UPDATE_OPTIONS,
        );
    }

    async markRetry(id: Types.ObjectId, retryCount: number, nextAttemptAt: Date, error: string): Promise<void> {
        await this.model.updateOne(
            { _id: id, status: 'PROCESSING' },
            { $set: { status: 'PENDING', retryCount, nextAttemptAt, processingStartedAt: null, lastError: error } },
            OUTBOX_UPDATE_OPTIONS,
        );
    }

    async markFailed(id: Types.ObjectId, error: string): Promise<void> {
        await this.model.updateOne(
            { _id: id, status: 'PROCESSING' },
            { $set: { status: 'FAILED', nextAttemptAt: null, processingStartedAt: null, lastError: error } },
            OUTBOX_UPDATE_OPTIONS,
        );
    }

    async reclaimStaleProcessing(staleThresholdMs: number): Promise<number> {
        const result = await this.model.updateMany(
            { status: 'PROCESSING', processingStartedAt: { $lt: new Date(Date.now() - staleThresholdMs) } },
            {
                $set: {
                    status: 'PENDING',
                    nextAttemptAt: new Date(),
                    processingStartedAt: null,
                    lastError: 'tombstone claim timed out',
                },
            },
            OUTBOX_UPDATE_OPTIONS,
        );
        return result.modifiedCount;
    }

    async retryFailed(): Promise<number> {
        const result = await this.model.updateMany(
            { status: 'FAILED' },
            { $set: { status: 'PENDING', retryCount: 0, nextAttemptAt: new Date(), processingStartedAt: null } },
            OUTBOX_UPDATE_OPTIONS,
        );
        return result.modifiedCount;
    }

    /** 주어진 메시지 중 tombstone이 존재하는 ID 집합. 중단된 삭제를 판별할 때 사용한다. */
    async findExistingMessageIds(messageIds: string[]): Promise<Set<string>> {
        if (messageIds.length === 0) return new Set();
        const rows = await this.model.find({ messageId: { $in: messageIds } }).select('messageId').lean();
        return new Set(rows.map((row) => row.messageId));
    }

    async countByStatus(): Promise<Record<MessageSearchTombstoneStatus, number>> {
        const rows = await this.model.aggregate<{ _id: MessageSearchTombstoneStatus; count: number }>([
            { $group: { _id: '$status', count: { $sum: 1 } } },
        ]);
        const counts = { PENDING: 0, PROCESSING: 0, DELETED: 0, FAILED: 0 } as Record<MessageSearchTombstoneStatus, number>;
        for (const row of rows) counts[row._id] = row.count;
        return counts;
    }

    /** 재구축 catch-up이 기준점 이후에 기록된 삭제만 재생하기 위해 순회한다. */
    scanUpdatedSince(since: Date, afterId: Types.ObjectId | null, batchSize: number): Promise<TombstoneRecord[]> {
        return this.model
            .find({ updatedAt: { $gte: since }, ...(afterId ? { _id: { $gt: afterId } } : {}) })
            .sort({ _id: 1 })
            .limit(batchSize)
            .lean<TombstoneRecord[]>();
    }
}
