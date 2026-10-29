import {Injectable as injectable} from '@nestjs/common';
import {InjectModel as injectModel} from '@nestjs/mongoose';
import {Model} from 'mongoose';
import {type Nullable} from '../common/type/nullable.js';
import {AttachmentUpload, ConfirmedAttachment, ATTACHMENT_UPLOAD_RETENTION_SECONDS} from './attachment-upload.schema.js';

@injectable()
export class AttachmentUploadRepository {
	constructor(@injectModel(AttachmentUpload.name) private readonly uploads: Model<AttachmentUpload>) {}

	private retryWindow(): {$gt: Date} {
		// TTL 삭제가 늦어져도 만료된 업로드를 새 메시지에 연결하지 않는다.
		return {$gt: new Date(Date.now() - (ATTACHMENT_UPLOAD_RETENTION_SECONDS * 1000))};
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
}
