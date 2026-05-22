import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
import { Message, MessageDocument } from '../schema/message.schema';

const FILE_ATTACHMENT_LIMIT = 100;

export type FileAttachmentRow = {
    messageId: string;
    fileName: string;
    fileSize: number;
    fileUrl: string;
    mimeType: string;
    uploaderId: number;
    uploadedAt: string;
    attachmentIndex: number;
};

export type CreateMessageInput = {
    teamId: number;
    projectId: number | null;
    channelId: number;
    authorId: number;
    content: string;
    type: string;
    attachments: Array<{
        name: string;
        url: string;
        size: number;
        mimeType: string;
    }>;
    parentMessageId: Types.ObjectId | null;
    clientMessageId?: string;
    mentions: number[];
    notificationStatus: string;
};

export type NotificationMessage = Message & { _id: Types.ObjectId; createdAt: Date };

@Injectable()
export class MessageRepository {
    constructor(
        @InjectModel(Message.name) private readonly messageModel: Model<Message>,
    ) {}

    findMessages(channelId: number, before?: string) {
        const query: Record<string, unknown> = { channelId };
        if (before) {
            query['_id'] = { $lt: new Types.ObjectId(before) };
        }

        return this.messageModel.aggregate([
            { $match: query },
            { $sort: { _id: -1 } },
            { $limit: 100 },
            {
                $lookup: {
                    from: this.messageModel.collection.name,
                    localField: 'parentMessageId',
                    foreignField: '_id',
                    as: 'mentionedMessage',
                    pipeline: [
                        {
                            $project: {
                                _id: 1,
                                authorId: 1,
                                content: 1,
                                type: 1,
                                createdAt: 1,
                            },
                        },
                    ],
                },
            },
            {
                $addFields: {
                    mentionedMessage: { $arrayElemAt: ['$mentionedMessage', 0] },
                },
            },
        ]);
    }

    findById(messageId: string): Promise<MessageDocument | null> {
        return this.messageModel.findById(messageId);
    }

    deleteById(messageId: string) {
        return this.messageModel.deleteOne({ _id: messageId });
    }

    createMessage(input: CreateMessageInput) {
        return this.messageModel.create(input);
    }

    createSystemMessage(
        teamId: number,
        channelId: number,
        content: string,
        projectId: number | null,
        authorId: number,
    ) {
        return this.messageModel.create({
            teamId,
            projectId,
            channelId,
            authorId,
            content,
            type: 'SYSTEM',
            attachments: [],
            mentions: [],
            clientMessageId: undefined,
            notificationStatus: 'SENT',
        });
    }

    async findFileAttachments(
        channelId: number,
        before: string | undefined,
        limit: number,
    ): Promise<{ items: FileAttachmentRow[]; nextCursor: string | null }> {
        const safeLimit = Math.min(Math.max(limit, 1), FILE_ATTACHMENT_LIMIT);
        const cursorMatch = before ? this.buildFileCursorMatch(before) : null;

        const pipeline: any[] = [
            {
                $match: {
                    channelId,
                    type: 'FILE',
                    'attachments.0': { $exists: true },
                },
            },
            {
                $unwind: {
                    path: '$attachments',
                    includeArrayIndex: 'attachmentIndex',
                },
            },
            ...(cursorMatch ? [{ $match: cursorMatch }] : []),
            {
                $sort: {
                    createdAt: -1,
                    _id: -1,
                    attachmentIndex: -1,
                },
            },
            { $limit: safeLimit + 1 },
            {
                $project: {
                    _id: 1,
                    authorId: 1,
                    createdAt: 1,
                    attachmentIndex: 1,
                    attachment: '$attachments',
                },
            },
        ];

        const rows = await this.messageModel.aggregate(pipeline);
        const hasNext = rows.length > safeLimit;
        const pageRows = rows.slice(0, safeLimit);
        const items: FileAttachmentRow[] = pageRows.map((row: any) => ({
            messageId: row._id.toString(),
            fileName: row.attachment.name,
            fileSize: row.attachment.size,
            fileUrl: row.attachment.url,
            mimeType: row.attachment.mimeType,
            uploaderId: row.authorId,
            uploadedAt: row.createdAt.toISOString(),
            attachmentIndex: row.attachmentIndex ?? 0,
        }));

        return {
            items,
            nextCursor: hasNext && pageRows.length > 0
                ? this.encodeFileCursor(pageRows.at(-1))
                : null,
        };
    }

    findOnePendingAndMarkProcessing(): Promise<NotificationMessage | null> {
        return this.messageModel.findOneAndUpdate(
            { notificationStatus: 'PENDING' },
            { $set: { notificationStatus: 'PROCESSING' } },
            { new: true },
        ).lean() as Promise<NotificationMessage | null>;
    }

    async findParentAuthorsByIds(parentIds: Types.ObjectId[]): Promise<Map<string, { authorId: number }>> {
        const parents = await this.messageModel
            .find({ _id: { $in: parentIds } })
            .select('authorId')
            .lean() as { _id: Types.ObjectId; authorId: number }[];

        return new Map(parents.map((parent) => [parent._id.toString(), { authorId: parent.authorId }]));
    }

    updateNotificationStatus(
        messageId: Types.ObjectId,
        notificationStatus: string,
        notificationRetryCount?: number,
    ) {
        const $set: Record<string, unknown> = { notificationStatus };
        if (notificationRetryCount !== undefined) {
            $set.notificationRetryCount = notificationRetryCount;
        }
        return this.messageModel.updateOne({ _id: messageId }, { $set });
    }

    private buildFileCursorMatch(before: string): Record<string, unknown> | null {
        const cursor = this.decodeFileCursor(before);
        if (!cursor) {
            return null;
        }

        return {
            $or: [
                { createdAt: { $lt: new Date(cursor.uploadedAt) } },
                {
                    createdAt: new Date(cursor.uploadedAt),
                    _id: { $lt: new Types.ObjectId(cursor.messageId) },
                },
                {
                    createdAt: new Date(cursor.uploadedAt),
                    _id: new Types.ObjectId(cursor.messageId),
                    attachmentIndex: { $lt: cursor.attachmentIndex },
                },
            ],
        };
    }

    private encodeFileCursor(row: any): string | null {
        if (!row) {
            return null;
        }

        return Buffer.from(JSON.stringify({
            uploadedAt: row.createdAt.toISOString(),
            messageId: row._id.toString(),
            attachmentIndex: row.attachmentIndex ?? 0,
        })).toString('base64');
    }

    private decodeFileCursor(before: string): { uploadedAt: string; messageId: string; attachmentIndex: number } | null {
        try {
            const parsed = JSON.parse(Buffer.from(before, 'base64').toString('utf8')) as {
                uploadedAt?: unknown;
                messageId?: unknown;
                attachmentIndex?: unknown;
            };

            if (
                typeof parsed.uploadedAt !== 'string' ||
                typeof parsed.messageId !== 'string' ||
                typeof parsed.attachmentIndex !== 'number' ||
                Number.isNaN(Date.parse(parsed.uploadedAt)) ||
                !Types.ObjectId.isValid(parsed.messageId)
            ) {
                return null;
            }

            return {
                uploadedAt: parsed.uploadedAt,
                messageId: parsed.messageId,
                attachmentIndex: parsed.attachmentIndex,
            };
        } catch {
            return null;
        }
    }
}
