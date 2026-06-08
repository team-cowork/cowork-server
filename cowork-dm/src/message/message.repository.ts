import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
import { DmMessage, DmMessageDocument } from './schema/dm-message.schema';

const MESSAGE_FETCH_LIMIT = 100;

export type CreateMessageInput = {
    conversationId: Types.ObjectId;
    authorId: number;
    content: string;
    type: string;
    attachments: Array<{ name: string; url: string; size: number; mimeType: string }>;
    clientMessageId?: string;
    mentions: number[];
    notificationStatus: string;
};

export type MessageRow = {
    _id: Types.ObjectId;
    conversationId: Types.ObjectId;
    authorId: number;
    content: string;
    type: string;
    attachments: Array<{ name: string; url: string; size: number; mimeType: string }>;
    isEdited: boolean;
    clientMessageId?: string | null;
    mentions: number[];
    reactions?: Array<{ emoji: string; userIds: number[] }>;
    createdAt: Date;
    updatedAt: Date;
};

export type NotificationMessage = DmMessage & { _id: Types.ObjectId; createdAt: Date };

type ReactionDoc = { reactions: Array<{ emoji: string; userIds: number[] }> };

/** DmMessage 컬렉션에 대한 데이터 접근 계층. */
@Injectable()
export class MessageRepository {
    constructor(
        @InjectModel(DmMessage.name) private readonly messageModel: Model<DmMessage>,
    ) {}

    /**
     * 대화방 메시지를 최신순으로 최대 100건 조회한다.
     *
     * @param conversationId - 대화방 ID
     * @param before - 이 메시지 ID 미만(`$lt`)의 메시지만 조회하는 커서
     * @returns 메시지 목록 (최신순)
     */
    findMessages(conversationId: string, before?: string): Promise<MessageRow[]> {
        const query: Record<string, unknown> = { conversationId: new Types.ObjectId(conversationId) };
        if (before) {
            query['_id'] = { $lt: new Types.ObjectId(before) };
        }

        return this.messageModel.aggregate([
            { $match: query },
            { $sort: { _id: -1 } },
            { $limit: MESSAGE_FETCH_LIMIT },
        ]);
    }

    /**
     * ID 로 메시지를 조회한다.
     *
     * @param messageId - 조회할 메시지 ID
     * @returns 메시지 도큐먼트, 없으면 `null`
     */
    findById(messageId: string): Promise<DmMessageDocument | null> {
        return this.messageModel.findById(messageId);
    }

    /**
     * ID 와 대화방 ID 로 메시지를 조회한다.
     *
     * 다른 대화방 메시지에 대한 접근을 방지하기 위해 conversationId 를 함께 검증한다.
     *
     * @param messageId - 조회할 메시지 ID
     * @param conversationId - 대화방 ID
     * @returns 메시지 도큐먼트, 없으면 `null`
     */
    findByIdAndConversationId(messageId: string, conversationId: string): Promise<DmMessageDocument | null> {
        return this.messageModel.findOne({
            _id: messageId,
            conversationId: new Types.ObjectId(conversationId),
        });
    }

    /**
     * 새 메시지를 생성한다.
     *
     * @param input - 메시지 생성 데이터
     * @returns 생성된 메시지 도큐먼트
     */
    createMessage(input: CreateMessageInput): Promise<DmMessageDocument> {
        return this.messageModel.create(input);
    }

    /**
     * 메시지를 삭제한다.
     *
     * @param messageId - 삭제할 메시지 ID
     */
    deleteById(messageId: string) {
        return this.messageModel.deleteOne({ _id: messageId });
    }

    /**
     * 메시지 내용을 수정하고 편집 이력을 기록한다.
     *
     * @param messageId - 수정할 메시지 ID
     * @param conversationId - 대화방 ID
     * @param content - 새 내용
     * @returns 수정된 메시지 도큐먼트, 없으면 `null`
     */
    async updateContent(messageId: string, conversationId: string, content: string): Promise<DmMessageDocument | null> {
        return this.messageModel.findOneAndUpdate(
            { _id: messageId, conversationId: new Types.ObjectId(conversationId) },
            {
                $set: { content, isEdited: true },
                $push: { editHistory: { $each: [{ content, editedAt: new Date() }], $position: 0 } },
            },
            { new: true },
        );
    }

    /**
     * 메시지에 이모지 리액션을 추가한다.
     *
     * 동시 요청을 고려해 최대 3단계로 처리한다.
     * 1. 기존 이모지 배열에 userId 추가 시도
     * 2. 실패 시 이모지 배열이 없는 경우 새 배열 삽입
     * 3. 여전히 실패 시 (다른 요청이 1과 2 사이에 삽입) 1단계 재시도
     *
     * @param conversationId - 대화방 ID
     * @param messageId - 리액션을 추가할 메시지 ID
     * @param emoji - 이모지 문자열
     * @param userId - 리액션한 사용자 ID
     * @returns 해당 이모지의 현재 리액션 수, 이미 리액션한 경우 `-1`, 메시지가 없으면 `null`
     */
    async addReaction(conversationId: string, messageId: string, emoji: string, userId: number): Promise<number | null> {
        const updated = await this.messageModel.findOneAndUpdate(
            {
                _id: messageId,
                conversationId: new Types.ObjectId(conversationId),
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
            conversationId: new Types.ObjectId(conversationId),
            reactions: { $elemMatch: { emoji, userIds: userId } },
        });
        if (alreadyReacted) return -1;

        const newDoc = await this.messageModel.findOneAndUpdate(
            {
                _id: messageId,
                conversationId: new Types.ObjectId(conversationId),
                'reactions.emoji': { $ne: emoji },
            },
            { $push: { reactions: { emoji, userIds: [userId] } } },
            { new: true },
        ).lean() as ReactionDoc | null;

        if (newDoc) {
            return newDoc.reactions.find(r => r.emoji === emoji)?.userIds.length ?? 1;
        }

        const retryUpdated = await this.messageModel.findOneAndUpdate(
            {
                _id: messageId,
                conversationId: new Types.ObjectId(conversationId),
                reactions: { $elemMatch: { emoji, userIds: { $ne: userId } } },
            },
            { $addToSet: { 'reactions.$[elem].userIds': userId } },
            { arrayFilters: [{ 'elem.emoji': emoji }], new: true },
        ).lean() as ReactionDoc | null;

        if (retryUpdated) {
            return retryUpdated.reactions.find(r => r.emoji === emoji)?.userIds.length ?? 0;
        }

        const messageExists = await this.messageModel.exists({
            _id: messageId,
            conversationId: new Types.ObjectId(conversationId),
        });
        return messageExists ? -1 : null;
    }

    /**
     * 메시지에서 이모지 리액션을 제거한다.
     *
     * `userIds` 가 비어 있는 리액션 배열은 자동으로 제거한다.
     *
     * @param conversationId - 대화방 ID
     * @param messageId - 리액션을 제거할 메시지 ID
     * @param emoji - 이모지 문자열
     * @param userId - 리액션을 취소할 사용자 ID
     * @returns 해당 이모지의 남은 리액션 수, 리액션이 없었던 경우 `-1`, 메시지가 없으면 `null`
     */
    async removeReaction(conversationId: string, messageId: string, emoji: string, userId: number): Promise<number | null> {
        const updated = await this.messageModel.findOneAndUpdate(
            {
                _id: messageId,
                conversationId: new Types.ObjectId(conversationId),
                reactions: { $elemMatch: { emoji, userIds: userId } },
            },
            { $pull: { 'reactions.$[elem].userIds': userId } },
            { arrayFilters: [{ 'elem.emoji': emoji }], new: true },
        ).lean() as ReactionDoc | null;

        if (!updated) {
            const messageExists = await this.messageModel.exists({
                _id: messageId,
                conversationId: new Types.ObjectId(conversationId),
            });
            return messageExists ? -1 : null;
        }

        const count = updated.reactions.find(r => r.emoji === emoji)?.userIds.length ?? 0;

        if (count === 0) {
            await this.messageModel.updateOne(
                { _id: messageId, conversationId: new Types.ObjectId(conversationId) },
                { $pull: { reactions: { emoji, userIds: { $size: 0 } } } },
            );
        }

        return count;
    }

    /**
     * `PENDING` 상태인 메시지 하나를 `PROCESSING` 으로 원자적으로 전환한다.
     *
     * Outbox Poller 가 알림 발행 전 중복 처리를 방지하기 위해 사용한다.
     *
     * @returns `PENDING` 메시지, 없으면 `null`
     */
    findOnePendingAndMarkProcessing(): Promise<NotificationMessage | null> {
        return this.messageModel.findOneAndUpdate(
            { notificationStatus: 'PENDING' },
            { $set: { notificationStatus: 'PROCESSING' } },
            { new: true },
        ).lean() as Promise<NotificationMessage | null>;
    }

    /**
     * 메시지의 알림 상태를 갱신한다.
     *
     * @param messageId - 대상 메시지 ID
     * @param notificationStatus - 변경할 상태 (`PENDING` | `PROCESSING` | `SENT` | `FAILED`)
     * @param notificationRetryCount - 재시도 횟수 (생략 시 변경하지 않음)
     */
    updateNotificationStatus(messageId: Types.ObjectId, notificationStatus: string, notificationRetryCount?: number) {
        const $set: Record<string, unknown> = { notificationStatus };
        if (notificationRetryCount !== undefined) {
            $set.notificationRetryCount = notificationRetryCount;
        }
        return this.messageModel.updateOne({ _id: messageId }, { $set });
    }
}
