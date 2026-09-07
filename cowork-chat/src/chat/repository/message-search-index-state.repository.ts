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
}
