import {Injectable as injectable, Logger, OnModuleInit} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model, Types} from 'mongoose';
import {type Nullable} from '../common/type/nullable.js';
import {
	AttachmentUpload,
	ConfirmedAttachment,
	ATTACHMENT_UPLOAD_RETENTION_SECONDS,
	ATTACHMENT_UPLOAD_TTL_SECONDS,
} from './attachment-upload.schema.js';

@injectable()
export class AttachmentUploadRepository implements OnModuleInit {
	private readonly logger = new Logger(AttachmentUploadRepository.name);

	constructor(@injectModel(AttachmentUpload.name) private readonly uploads: Model<AttachmentUpload>) {}

	private retryWindow(): {$gt: Date} {
		// TTL 삭제가 늦어져도 만료된 업로드를 새 메시지에 연결하지 않는다.
		return {$gt: new Date(Date.now() - (ATTACHMENT_UPLOAD_RETENTION_SECONDS * 1000))};
	}

	/**
     기존 TTL 인덱스의 만료 시간을 갱신한다. 같은 키의 인덱스는 옵션만 바꿔 다시 만들 수 없어 autoIndex로는 반영되지 않는다.
     */
	async onModuleInit(): Promise<void> {
		try {
			await this.uploads.db.db?.command({
				collMod: this.uploads.collection.collectionName,
				index: {keyPattern: {createdAt: 1}, expireAfterSeconds: ATTACHMENT_UPLOAD_TTL_SECONDS},
			});
		} catch (error) {
			// 새 DB에는 collection·인덱스가 아직 없고, autoIndex가 새 값으로 만든다.
			this.logger.warn(`Attachment upload TTL index was not updated: ${String(error)}`);
		}
	}

	async create(upload: AttachmentUpload): Promise<void> {
		await this.uploads.create(upload);
	}

	async find(uploadKey: string): Promise<Nullable<AttachmentUpload>> {
		return this.uploads.findOne({uploadKey, createdAt: this.retryWindow()}).lean();
	}

	async confirm(uploadKey: string, attachment: ConfirmedAttachment): Promise<Nullable<AttachmentUpload>> {
		// Concurrent confirmations copy to distinct keys. Only this CAS winner becomes reachable.
		return this.uploads.findOneAndUpdate(
			{uploadKey, createdAt: this.retryWindow(), objectKey: {$exists: false}},
			{$set: {objectKey: attachment.objectKey, size: attachment.size}},
			{returnDocument: 'after'},
		).lean();
	}

	async claim(objectKey: string, channelId: number, userId: number, clientMessageId: string): Promise<Nullable<AttachmentUpload>> {
		return this.uploads.findOneAndUpdate(
			{
				objectKey, channelId, userId, createdAt: this.retryWindow(), $or: [{clientMessageId: {$exists: false}}, {clientMessageId}],
			},
			{$set: {clientMessageId}},
			{returnDocument: 'after'},
		).lean();
	}

	/**
     `createdBefore`보다 오래된 기록을 최대 `batchSize`개 회수 대상으로 점유한다. `reclaimAttemptAt`을 `leaseMs` 뒤로
     미뤄 다른 replica가 같은 기록을 처리하지 않게 하고, 실패한 기록은 점유가 끝나면 다시 처리된다.
     `createdAt` TTL 인덱스로 오래된 순서대로 찾는다.
     */
	async claimReclaimable(createdBefore: Date, now: Date, leaseMs: number, batchSize: number): Promise<AttachmentUpload[]> {
		const filter = {createdAt: {$lt: createdBefore}, reclaimAttemptAt: {$not: {$gt: now}}};
		const candidates = await this.uploads.find(filter).sort({createdAt: 1}).limit(batchSize).select('_id').lean();
		if (candidates.length === 0) {
			return [];
		}

		const ids = candidates.map(candidate => candidate._id);
		const reclaimClaimId = new Types.ObjectId().toString();
		await this.uploads.updateMany(
			{...filter, _id: {$in: ids}},
			{$set: {reclaimAttemptAt: new Date(now.getTime() + leaseMs), reclaimClaimId}},
			{timestamps: false},
		);
		return this.uploads.find({_id: {$in: ids}, reclaimClaimId}).lean();
	}

	async remove(uploadKey: string): Promise<void> {
		await this.uploads.deleteOne({uploadKey});
	}
}
