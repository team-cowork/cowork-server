import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, Types} from 'mongoose';
import {UPLOAD_RECLAIM_AGE_MS} from '../../storage/attachment-upload.schema.js';
import {MeetingNoteAttachmentRetention} from '../schema/meeting-note-attachment-retention.schema.js';

export type ClaimedAttachmentRetention = MeetingNoteAttachmentRetention & {claimId: string};

@injectable()
export class MeetingNoteAttachmentRetentionRepository {
	constructor(@injectModel(MeetingNoteAttachmentRetention.name) private readonly model: Model<MeetingNoteAttachmentRetention>) {}

	async record(objectKeys: string[]): Promise<void> {
		if (objectKeys.length === 0) {
			return;
		}

		// 메시지 전송 재시도 창과 유예가 끝난 뒤부터 참조 여부를 재확인한다.
		const nextAttemptAt = new Date(Date.now() + UPLOAD_RECLAIM_AGE_MS);
		await this.model.bulkWrite(objectKeys.map(objectKey => ({
			updateOne: {filter: {objectKey}, update: {$setOnInsert: {objectKey, nextAttemptAt}}, upsert: true},
		})));
	}

	async claimDue(now: Date, leaseMs: number, batchSize: number): Promise<ClaimedAttachmentRetention[]> {
		const candidates = await this.model.find({nextAttemptAt: {$lte: now}}).sort({nextAttemptAt: 1}).limit(batchSize).select('_id').lean();
		if (candidates.length === 0) {
			return [];
		}

		const ids = candidates.map(candidate => candidate._id);
		const claimId = new Types.ObjectId().toString();
		await this.model.updateMany(
			{_id: {$in: ids}, nextAttemptAt: {$lte: now}},
			{$set: {nextAttemptAt: new Date(now.getTime() + leaseMs), claimId}},
		);
		return this.model.find({_id: {$in: ids}, claimId}).lean<ClaimedAttachmentRetention[]>();
	}

	async remove(objectKey: string, claimId: string): Promise<void> {
		await this.model.deleteOne({objectKey, claimId});
	}
}
