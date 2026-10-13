import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model } from 'mongoose';
import { AttachmentUpload, ConfirmedAttachment } from './attachment-upload.schema';

@Injectable()
export class AttachmentUploadRepository {
    constructor(@InjectModel(AttachmentUpload.name) private readonly uploads: Model<AttachmentUpload>) {}

    async create(upload: AttachmentUpload): Promise<void> {
        await this.uploads.create(upload);
    }

    async find(uploadKey: string): Promise<AttachmentUpload | null> {
        return this.uploads.findOne({ uploadKey }).lean();
    }

    async confirm(uploadKey: string, attachment: ConfirmedAttachment): Promise<AttachmentUpload | null> {
        // Concurrent confirmations copy to distinct keys. Only this CAS winner becomes reachable.
        return this.uploads.findOneAndUpdate(
            { uploadKey, objectKey: { $exists: false } },
            { $set: { objectKey: attachment.objectKey, size: attachment.size } },
            { returnDocument: 'after' },
        ).lean();
    }

    async claim(objectKey: string, channelId: number, userId: number, clientMessageId: string): Promise<AttachmentUpload | null> {
        return this.uploads.findOneAndUpdate(
            {
                objectKey, channelId, userId, $or: [{ clientMessageId: { $exists: false } }, { clientMessageId }],
            },
            { $set: { clientMessageId } },
            { returnDocument: 'after' },
        ).lean();
    }
}
