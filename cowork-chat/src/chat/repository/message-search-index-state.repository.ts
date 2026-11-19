import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model} from 'mongoose';
import {MESSAGE_SEARCH_INDEX_STATE_ID, MessageSearchIndexState} from '../schema/index.js';
import {type Nullable} from '../../common/type/nullable.js';

/**
재구축 결과와 재개 위치를 기록하는 단일 도큐먼트 저장소.
*/
@injectable()
export class MessageSearchIndexStateRepository {
	constructor(
		@injectModel(MessageSearchIndexState.name) private readonly model: Model<MessageSearchIndexState>,
	) {}

	private async patch(fields: Partial<MessageSearchIndexState>): Promise<void> {
		await this.model.updateOne({_id: MESSAGE_SEARCH_INDEX_STATE_ID}, {$set: fields}, {upsert: true});
	}

	async get(): Promise<Nullable<MessageSearchIndexState>> {
		return this.model.findById(MESSAGE_SEARCH_INDEX_STATE_ID).lean<MessageSearchIndexState>();
	}

	async markRebuildStarted(index: string, startedAt: Date, scanCursor: Nullable<string>): Promise<void> {
		return this.patch({
			lastRebuildStartedAt: startedAt,
			lastRebuildIndex: index,
			lastRebuildScanCursor: scanCursor,
			lastRebuildError: null,
		});
	}

	/**
    Snapshot scan 진행 위치를 남겨 중단된 재구축을 이어서 재개할 수 있게 한다.
    */
	async saveRebuildScanCursor(scanCursor: Nullable<string>): Promise<void> {
		return this.patch({lastRebuildScanCursor: scanCursor});
	}

	async markRebuildSucceeded(index: string, documentCount: number): Promise<void> {
		return this.patch({
			lastRebuildSucceededAt: new Date(),
			lastRebuildIndex: index,
			lastRebuildDocumentCount: documentCount,
			lastRebuildScanCursor: null,
			lastRebuildError: null,
		});
	}

	async markRebuildFailed(error: string): Promise<void> {
		return this.patch({lastRebuildFailedAt: new Date(), lastRebuildError: error});
	}
}
