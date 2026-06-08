import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, Types } from 'mongoose';
import { DmConversation, DmConversationDocument } from './schema/dm-conversation.schema';

/** DmConversation 컬렉션에 대한 데이터 접근 계층. */
@Injectable()
export class ConversationRepository {
    constructor(
        @InjectModel(DmConversation.name) private readonly model: Model<DmConversation>,
    ) {}

    /**
     * 두 userId 쌍으로 대화방을 찾거나 없으면 생성한다 (upsert).
     *
     * userId 를 오름차순 정렬하여 저장하므로 인수 순서에 관계없이 동일한 대화방이 반환된다.
     *
     * @param userIdA - 첫 번째 사용자 ID
     * @param userIdB - 두 번째 사용자 ID
     * @returns 기존 또는 새로 생성된 대화방
     */
    async findOrCreate(userIdA: number, userIdB: number): Promise<DmConversationDocument> {
        const sorted = [userIdA, userIdB].sort((a, b) => a - b);

        const existing = await this.model.findOne({
            'participants.userId': { $all: sorted },
            $expr: { $eq: [{ $size: '$participants' }, 2] },
        });

        if (existing) return existing;

        return this.model.create({
            participants: sorted.map((userId) => ({
                userId,
                isHidden: false,
                lastReadMessageId: null,
                unreadCount: 0,
            })),
            lastMessageId: null,
            lastMessageAt: null,
        });
    }

    /**
     * ID 로 대화방을 조회한다.
     *
     * @param conversationId - 조회할 대화방 ID
     * @returns 대화방 도큐먼트, 없으면 `null`
     */
    findById(conversationId: string): Promise<DmConversationDocument | null> {
        return this.model.findById(conversationId);
    }

    /**
     * userId 가 참여 중이고 숨기지 않은 대화방 목록을 최신 메시지 순으로 반환한다.
     *
     * @param userId - 조회할 사용자 ID
     * @returns 대화방 목록 (lastMessageAt 내림차순)
     */
    findVisibleByUserId(userId: number): Promise<DmConversationDocument[]> {
        return this.model.find({
            participants: { $elemMatch: { userId, isHidden: false } },
        }).sort({ lastMessageAt: -1 });
    }

    /**
     * 특정 참여자에게 대화방을 숨긴다 (`isHidden = true`).
     *
     * @param conversationId - 대화방 ID
     * @param userId - 숨길 참여자 ID
     * @returns 업데이트된 대화방, 없으면 `null`
     */
    hideForUser(conversationId: string, userId: number): Promise<DmConversationDocument | null> {
        return this.model.findOneAndUpdate(
            { _id: conversationId, 'participants.userId': userId },
            { $set: { 'participants.$.isHidden': true } },
            { new: true },
        );
    }

    /**
     * 메시지 수신 시 수신자의 `isHidden` 을 false 로 복구하고 `unreadCount` 를 증가시킨다.
     *
     * @param conversationId - 대화방 ID
     * @param receiverId - 수신자 ID
     * @param messageId - 수신된 메시지 ID
     * @param sentAt - 메시지 전송 시각
     */
    async onMessageReceived(
        conversationId: Types.ObjectId,
        receiverId: number,
        messageId: Types.ObjectId,
        sentAt: Date,
    ): Promise<void> {
        await this.model.updateOne(
            { _id: conversationId, 'participants.userId': receiverId },
            {
                $set: {
                    'participants.$.isHidden': false,
                    lastMessageId: messageId,
                    lastMessageAt: sentAt,
                },
                $inc: { 'participants.$.unreadCount': 1 },
            },
        );
    }

    /**
     * 메시지 전송 시 발신자의 `lastMessageId` 와 `lastMessageAt` 을 갱신한다.
     *
     * 발신자의 `unreadCount` 는 변경하지 않는다.
     *
     * @param conversationId - 대화방 ID
     * @param senderId - 발신자 ID
     * @param messageId - 전송된 메시지 ID
     * @param sentAt - 메시지 전송 시각
     */
    async onMessageSent(
        conversationId: Types.ObjectId,
        senderId: number,
        messageId: Types.ObjectId,
        sentAt: Date,
    ): Promise<void> {
        await this.model.updateOne(
            { _id: conversationId, 'participants.userId': senderId },
            {
                $set: {
                    lastMessageId: messageId,
                    lastMessageAt: sentAt,
                },
            },
        );
    }

    /**
     * 읽음 처리: `unreadCount` 를 0 으로 초기화하고 `lastReadMessageId` 를 갱신한다.
     *
     * @param conversationId - 대화방 ID
     * @param userId - 읽음 처리할 참여자 ID
     * @param messageId - 읽은 마지막 메시지 ID
     */
    async markRead(conversationId: string, userId: number, messageId: string): Promise<void> {
        await this.model.updateOne(
            { _id: conversationId, 'participants.userId': userId },
            {
                $set: {
                    'participants.$.unreadCount': 0,
                    'participants.$.lastReadMessageId': new Types.ObjectId(messageId),
                },
            },
        );
    }

    /**
     * 대화방에서 `userId` 를 제외한 나머지 참여자 ID 를 반환한다.
     *
     * @param conversation - 대화방 도큐먼트
     * @param userId - 제외할 사용자 ID
     * @returns 다른 참여자 ID, 참여자가 한 명뿐이면 `null`
     */
    getOtherParticipantId(conversation: DmConversationDocument, userId: number): number | null {
        const other = conversation.participants.find((p) => p.userId !== userId);
        return other?.userId ?? null;
    }
}
