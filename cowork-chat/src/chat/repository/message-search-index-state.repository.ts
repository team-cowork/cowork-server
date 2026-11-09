import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model} from 'mongoose';
import {MESSAGE_SEARCH_INDEX_STATE_ID, MessageSearchIndexState} from '../schema/index.js';
import {type Nullable} from '../../common/type/nullable.js';

/**
재구축 결과와 백필 진행 상태를 replica 간에 공유하는 단일 도큐먼트 저장소.
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

	async markLegacyBackfillCompleted(): Promise<void> {
		return this.patch({legacyBackfillCompletedAt: new Date()});
	}

	/**
     재구축 락을 점유한다.

     락이 비어 있거나, 이 `lockId`가 이미 쥐고 있거나, 마지막 점유가 `staleThresholdMs`보다
     오래됐으면(죽은 프로세스로 간주) 점유에 성공한다. 그 외에는 다른 replica가 실행 중인
     것이므로 실패한다.
     */
	async tryAcquireRebuildLock(lockId: string, staleThresholdMs: number): Promise<boolean> {
		const now = new Date();
		const staleBefore = new Date(now.getTime() - staleThresholdMs);
		try {
			const result = await this.model.updateOne(
				{
					_id: MESSAGE_SEARCH_INDEX_STATE_ID,
					$or: [
						{rebuildLockedAt: null},
						{rebuildLockedAt: {$lt: staleBefore}},
						{rebuildLockId: lockId},
					],
				},
				{$set: {rebuildLockedAt: now, rebuildLockId: lockId}},
				{upsert: true},
			);
			return result.matchedCount > 0 || result.upsertedCount > 0;
		} catch (error) {
			// 다른 holder의 최근 락이 있으면 필터가 빗나가 upsert가 같은 `_id`를 삽입하려다 E11000이 난다. 점유 실패와 같다.
			if ((error as {code?: unknown}).code === 11_000) {
				return false;
			}

			throw error;
		}
	}

	/**
    이 `lockId`가 아직 쥔 재구축 락의 점유 시각을 갱신한다. 락을 잃었으면 `false`를 반환한다.
    */
	async renewRebuildLock(lockId: string): Promise<boolean> {
		const result = await this.model.updateOne(
			{_id: MESSAGE_SEARCH_INDEX_STATE_ID, rebuildLockId: lockId},
			{$set: {rebuildLockedAt: new Date()}},
		);
		return result.matchedCount > 0;
	}

	/**
    이 `lockId`가 쥔 재구축 락을 해제한다. 이미 다른 락으로 넘어갔다면 아무것도 하지 않는다.
    */
	async releaseRebuildLock(lockId: string): Promise<void> {
		await this.model.updateOne(
			{_id: MESSAGE_SEARCH_INDEX_STATE_ID, rebuildLockId: lockId},
			{$set: {rebuildLockedAt: null, rebuildLockId: null}},
		);
	}
}
