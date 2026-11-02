import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, Types} from 'mongoose';
import {AttachmentDeletion} from '../schema/index.js';

export type AttachmentDeletionRecord = AttachmentDeletion & {_id: Types.ObjectId};

@injectable()
export class AttachmentDeletionRepository {
	constructor(@injectModel(AttachmentDeletion.name) private readonly model: Model<AttachmentDeletion>) {}

	/**
    삭제 의도를 멱등하게 기록한다. 같은 메시지를 다시 지우면 요청 시각만 갱신한다.
    */
	async record(input: {messageId: string; channelId: number; objectKeys: string[]}): Promise<void> {
		const now = new Date();
		await this.model.updateOne(
			{messageId: input.messageId},
			{$set: {requestedAt: now, nextAttemptAt: now}, $setOnInsert: {channelId: input.channelId, objectKeys: input.objectKeys}},
			{upsert: true},
		);
	}

	/**
     처리 시각이 된 의도를 최대 `batchSize`개 점유한다. `nextAttemptAt`을 `leaseMs` 뒤로 미뤄 다른 replica가
     같은 의도를 처리하지 않게 하고, 실패한 의도는 점유가 끝나면 다시 처리된다.
     되읽기는 호출마다 발급한 `claimId`로 해 실제로 점유한 의도만 돌려준다.
     */
	async claimDue(now: Date, leaseMs: number, batchSize: number): Promise<AttachmentDeletionRecord[]> {
		const candidates = await this.model
			.find({nextAttemptAt: {$lte: now}})
			.sort({nextAttemptAt: 1})
			.limit(batchSize)
			.select('_id')
			.lean();
		if (candidates.length === 0) {
			return [];
		}

		const ids = candidates.map(candidate => candidate._id);
		const claimId = new Types.ObjectId().toString();
		await this.model.updateMany(
			{_id: {$in: ids}, nextAttemptAt: {$lte: now}},
			{$set: {nextAttemptAt: new Date(now.getTime() + leaseMs), claimId}},
		);
		return this.model.find({_id: {$in: ids}, claimId}).lean<AttachmentDeletionRecord[]>();
	}

	async markFailed(id: Types.ObjectId, error: string): Promise<void> {
		await this.model.updateOne({_id: id}, {$inc: {attempts: 1}, $set: {lastError: error}});
	}

	/**
    점유 이후 같은 메시지 삭제가 다시 요청됐으면 지우지 않는다.
    */
	async remove(id: Types.ObjectId, requestedAt: Date): Promise<void> {
		await this.model.deleteOne({_id: id, requestedAt});
	}
}
