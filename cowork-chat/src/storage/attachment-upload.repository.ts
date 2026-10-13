import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model } from 'mongoose';
import { AttachmentUpload, ConfirmedAttachment, ATTACHMENT_UPLOAD_RETENTION_SECONDS } from './attachment-upload.schema';

@Injectable()
export class AttachmentUploadRepository {
    constructor(@InjectModel(AttachmentUpload.name) private readonly uploads: Model<AttachmentUpload>) {}

    async create(upload: AttachmentUpload): Promise<void> {
        await this.uploads.create(upload);
    }

    async find(uploadKey: string): Promise<AttachmentUpload | null> {
        return this.uploads.findOne({ uploadKey, createdAt: this.retryWindow() }).lean();
    }

    async confirm(uploadKey: string, attachment: ConfirmedAttachment): Promise<AttachmentUpload | null> {
        // Concurrent confirmations copy to distinct keys. Only this CAS winner becomes reachable.
        return this.uploads.findOneAndUpdate(
            { uploadKey, createdAt: this.retryWindow(), objectKey: { $exists: false } },
            { $set: { objectKey: attachment.objectKey, size: attachment.size } },
            { returnDocument: 'after' },
        ).lean();
    }

    async claim(objectKey: string, channelId: number, userId: number, clientMessageId: string): Promise<AttachmentUpload | null> {
        return this.uploads.findOneAndUpdate(
            {
                objectKey, channelId, userId, createdAt: this.retryWindow(), $or: [{ clientMessageId: { $exists: false } }, { clientMessageId }],
            },
            { $set: { clientMessageId } },
            { returnDocument: 'after' },
        ).lean();
    }

    private retryWindow(): { $gt: Date } {
        // TTL 삭제가 늦어져도 만료된 업로드를 새 메시지에 연결하지 않는다.
        return { $gt: new Date(Date.now() - (ATTACHMENT_UPLOAD_RETENTION_SECONDS * 1000)) };
    }
}
