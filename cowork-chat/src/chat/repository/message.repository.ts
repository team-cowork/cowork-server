import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
import { Message, MessageDocument } from '../schema/message.schema';

const MESSAGE_FETCH_LIMIT = 100;
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

export type MentionedMessageRow = {
    _id: Types.ObjectId;
    authorId: number;
    content: string;
    type: string;
    createdAt: Date;
};

export type MessageRow = {
    _id: Types.ObjectId;
    teamId: number;
    projectId: number | null;
    channelId: number;
    authorId: number;
    content: string;
    type: string;
    attachments: Array<{ name: string; url: string; size: number; mimeType: string }>;
    parentMessageId: Types.ObjectId | null;
    isEdited: boolean;
    isPinned: boolean;
    clientMessageId?: string | null;
    mentions: number[];
    reactions?: Array<{ emoji: string; userIds: number[] }>;
    createdAt: Date;
    updatedAt: Date;
    mentionedMessage: MentionedMessageRow | null;
};

type ReactionDoc = { reactions: Array<{ emoji: string; userIds: number[] }> };

@Injectable()
export class MessageRepository {
    constructor(
        @InjectModel(Message.name) private readonly messageModel: Model<Message>,
    ) {}

    findMessages(channelId: number, before?: string): Promise<MessageRow[]> {
        const query: Record<string, unknown> = { channelId };
        if (before) {
            query['_id'] = { $lt: new Types.ObjectId(before) };
        }

        return this.messageModel.aggregate([
            { $match: query },
            { $sort: { _id: -1 } },
            { $limit: MESSAGE_FETCH_LIMIT },
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

    findPinnedMessages(channelId: number): Promise<MessageRow[]> {
        return this.messageModel.aggregate([
            { $match: { channelId, isPinned: true } },
            { $sort: { _id: -1 } },
            { $limit: MESSAGE_FETCH_LIMIT },
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

    async findParentAuthorsByIds(parentIds: Types.ObjectId[]): Promise<Map<string, { authorId: number }>> {
        const parents = await this.messageModel
            .find({ _id: { $in: parentIds } })
            .select('authorId')
            .lean() as { _id: Types.ObjectId; authorId: number }[];

        return new Map(parents.map((parent) => [parent._id.toString(), { authorId: parent.authorId }]));
    }

    async addReaction(channelId: number, messageId: string, emoji: string, userId: number): Promise<number | null> {
        const updated = await this.messageModel.findOneAndUpdate(
            {
                _id: messageId,
                channelId,
                reactions: { $elemMatch: { emoji, userIds: { $ne: userId } } },
            },
            { $addToSet: { 'reactions.$[elem].userIds': userId } },
            { arrayFilters: [{ 'elem.emoji': emoji }], new: true },
        ).lean() as ReactionDoc | null;

        if (updated) {
            return updated.reactions.find(r => r.emoji === emoji)?.userIds.length ?? 0;
        }

        const alreadyReacted = await this.messageModel.exists({
            _id: messageId,
            channelId,
            reactions: { $elemMatch: { emoji, userIds: userId } },
        });
        if (alreadyReacted) return -1;

        const newDoc = await this.messageModel.findOneAndUpdate(
            { _id: messageId, channelId, 'reactions.emoji': { $ne: emoji } },
            { $push: { reactions: { emoji, userIds: [userId] } } },
            { new: true },
        ).lean() as ReactionDoc | null;

        if (newDoc) {
            return newDoc.reactions.find(r => r.emoji === emoji)?.userIds.length ?? 1;
        }

        // 동시 요청으로 emoji 항목이 생성된 경우 재시도
        const retryUpdated = await this.messageModel.findOneAndUpdate(
            {
                _id: messageId,
                channelId,
                reactions: { $elemMatch: { emoji, userIds: { $ne: userId } } },
            },
            { $addToSet: { 'reactions.$[elem].userIds': userId } },
            { arrayFilters: [{ 'elem.emoji': emoji }], new: true },
        ).lean() as ReactionDoc | null;

        if (retryUpdated) {
            return retryUpdated.reactions.find(r => r.emoji === emoji)?.userIds.length ?? 0;
        }

        const messageExists = await this.messageModel.exists({ _id: messageId, channelId });
        return messageExists ? -1 : null;
    }

    async removeReaction(channelId: number, messageId: string, emoji: string, userId: number): Promise<number | null> {
        const updated = await this.messageModel.findOneAndUpdate(
            {
                _id: messageId,
                channelId,
                reactions: { $elemMatch: { emoji, userIds: userId } },
            },
            { $pull: { 'reactions.$[elem].userIds': userId } },
            { arrayFilters: [{ 'elem.emoji': emoji }], new: true },
        ).lean() as ReactionDoc | null;

        if (!updated) {
            const messageExists = await this.messageModel.exists({ _id: messageId, channelId });
            return messageExists ? -1 : null;
        }

        const count = updated.reactions.find(r => r.emoji === emoji)?.userIds.length ?? 0;

        if (count === 0) {
            await this.messageModel.updateOne(
                { _id: messageId, channelId },
                { $pull: { reactions: { emoji, userIds: { $size: 0 } } } },
            );
        }

        return count;
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
