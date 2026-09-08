import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model } from 'mongoose';
import {
    MESSAGE_SEARCH_INDEX_STATE_ID,
    MessageSearchIndexState,
} from '../schema/message-search-index-state.schema';

/** 재구축 결과와 백필 진행 상태를 replica 간에 공유하는 단일 도큐먼트 저장소. */
@Injectable()
export class MessageSearchIndexStateRepository {
    constructor(
        @InjectModel(MessageSearchIndexState.name) private readonly model: Model<MessageSearchIndexState>,
    ) {}

    get(): Promise<MessageSearchIndexState | null> {
        return this.model.findById(MESSAGE_SEARCH_INDEX_STATE_ID).lean<MessageSearchIndexState>();
    }

    private async patch(fields: Partial<MessageSearchIndexState>): Promise<void> {
        await this.model.updateOne({ _id: MESSAGE_SEARCH_INDEX_STATE_ID }, { $set: fields }, { upsert: true });
    }

    markRebuildStarted(index: string, startedAt: Date, scanCursor: string | null): Promise<void> {
        return this.patch({
            lastRebuildStartedAt: startedAt,
            lastRebuildIndex: index,
            lastRebuildScanCursor: scanCursor,
            lastRebuildError: null,
        });
    }

    /** snapshot scan 진행 위치를 남겨 중단된 재구축을 이어서 재개할 수 있게 한다. */
    saveRebuildScanCursor(scanCursor: string | null): Promise<void> {
        return this.patch({ lastRebuildScanCursor: scanCursor });
    }

    markRebuildSucceeded(index: string, documentCount: number): Promise<void> {
        return this.patch({
            lastRebuildSucceededAt: new Date(),
            lastRebuildIndex: index,
            lastRebuildDocumentCount: documentCount,
            lastRebuildScanCursor: null,
            lastRebuildError: null,
        });
    }

    markRebuildFailed(error: string): Promise<void> {
        return this.patch({ lastRebuildFailedAt: new Date(), lastRebuildError: error });
    }

    markLegacyBackfillCompleted(): Promise<void> {
        return this.patch({ legacyBackfillCompletedAt: new Date() });
    }

    /**
     * 재구축 락을 점유한다.
     *
     * 락이 비어 있거나, 이 `lockId`가 이미 쥐고 있거나, 마지막 점유가 `staleThresholdMs`보다
     * 오래됐으면(죽은 프로세스로 간주) 점유에 성공한다. 그 외에는 다른 replica가 실행 중인
     * 것이므로 실패한다.
     */
    async tryAcquireRebuildLock(lockId: string, staleThresholdMs: number): Promise<boolean> {
        const now = new Date();
        const staleBefore = new Date(now.getTime() - staleThresholdMs);
        const result = await this.model.updateOne(
            {
                _id: MESSAGE_SEARCH_INDEX_STATE_ID,
                $or: [
                    { rebuildLockedAt: null },
                    { rebuildLockedAt: { $lt: staleBefore } },
                    { rebuildLockId: lockId },
                ],
            },
            { $set: { rebuildLockedAt: now, rebuildLockId: lockId } },
            { upsert: true },
        );
        return result.matchedCount > 0 || result.upsertedCount > 0;
    }

    /** 이 `lockId`가 쥔 재구축 락을 해제한다. 이미 다른 락으로 넘어갔다면 아무것도 하지 않는다. */
    async releaseRebuildLock(lockId: string): Promise<void> {
        await this.model.updateOne(
            { _id: MESSAGE_SEARCH_INDEX_STATE_ID, rebuildLockId: lockId },
            { $set: { rebuildLockedAt: null, rebuildLockId: null } },
        );
    }
}
