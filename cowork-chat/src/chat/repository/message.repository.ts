import { Injectable } from '@nestjs/common';
import { InjectModel } from '@nestjs/mongoose';
import { Model, PipelineStage, Types } from 'mongoose';
import { Message, MessageDocument } from '../schema/message.schema';

/** 한 번에 조회하는 최대 메시지 수 */
const MESSAGE_FETCH_LIMIT = 100;

/** 한 번에 조회하는 최대 파일 첨부 항목 수 */
const FILE_ATTACHMENT_LIMIT = 100;

/**
 * 파일 첨부 목록 조회 결과의 단일 행.
 *
 * `$unwind`로 펼쳐진 각 첨부 파일을 나타내며,
 * `fileId`는 커서 기반 페이지네이션에 사용되는 base64 인코딩 커서 문자열입니다.
 */
export type FileAttachmentRow = {
    /** 커서 기반 페이지네이션에 사용되는 base64 인코딩 커서 값 */
    fileId: string;
    /** 첨부 파일이 속한 메시지의 ObjectId 문자열 */
    messageId: string;
    fileName: string;
    fileSize: number;
    fileUrl: string;
    mimeType: string;
    /** 파일을 업로드한 메시지 작성자의 사용자 ID */
    uploaderId: number;
    /** 메시지 생성 시각 (ISO 8601 문자열) */
    uploadedAt: string;
    /** 메시지 내 첨부 파일 배열에서의 0-based 인덱스 */
    attachmentIndex: number;
};

/** {@link findFileAttachments}의 집계 파이프라인 `$project` 결과 행 타입. */
type FileAttachmentAggregateRow = {
    _id: Types.ObjectId;
    authorId: number;
    createdAt: Date;
    attachmentIndex?: number;
    attachment: { name: string; url: string; size: number; mimeType: string };
};

/**
 * 메시지 생성 시 필요한 입력 데이터 타입.
 * `notificationStatus`는 생성 시점에 외부에서 명시적으로 지정합니다.
 */
export type CreateMessageInput = {
    /** DM 채널 메시지는 팀에 속하지 않으므로 null */
    teamId: number | null;
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

/**
 * 알림 파이프라인에서 사용하는 메시지 타입.
 * `_id`와 `createdAt`이 항상 존재함을 보장합니다.
 */
export type NotificationMessage = Message & { _id: Types.ObjectId; createdAt: Date };

/**
 * `$lookup`으로 조인된 부모(스레드 원본) 메시지의 요약 정보.
 * 메시지 목록 응답에서 `mentionedMessage` 필드로 포함됩니다.
 */
export type MentionedMessageRow = {
    _id: Types.ObjectId;
    authorId: number;
    content: string;
    type: string;
    createdAt: Date;
};

/**
 * 메시지 목록 조회 결과의 단일 행.
 * `mentionedMessage`는 `parentMessageId`가 있을 때만 채워지며, 없으면 `null`입니다.
 */
export type MessageRow = {
    _id: Types.ObjectId;
    teamId: number | null;
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
    /** 스레드 부모 메시지 요약. 부모가 없거나 삭제된 경우 `null` */
    mentionedMessage: MentionedMessageRow | null;
};

/** `addReaction` / `removeReaction` 내부에서 reactions 배열만 추출하기 위한 타입 */
type ReactionDoc = { reactions: Array<{ emoji: string; userIds: number[] }> };

/**
 * 채팅 메시지 도큐먼트에 대한 데이터 접근 객체.
 *
 * 단순 CRUD 외에 커서 기반 페이지네이션, 이모지 반응 동시성 처리,
 * 아웃박스(outbox) 패턴 알림 상태 관리 등의 비자명한 로직을 캡슐화합니다.
 */
@Injectable()
export class MessageRepository {
    constructor(
        @InjectModel(Message.name) private readonly messageModel: Model<Message>,
    ) {}

    /**
     * 채널의 메시지 목록을 최신순으로 최대 100개 조회합니다.
     *
     * `before`가 주어지면 해당 ObjectId보다 작은(이전) 메시지만 조회하여
     * 커서 기반 무한 스크롤을 구현합니다.
     *
     * `$lookup`으로 `parentMessageId`에 해당하는 부모 메시지를 `mentionedMessage` 필드에
     * 조인합니다. 부모 메시지가 없거나 삭제된 경우 `mentionedMessage`는 `null`입니다.
     *
     * @param channelId - 조회할 채널의 식별자
     * @param before - 이 ObjectId 문자열보다 이전 메시지를 조회하는 커서. 생략 시 최신부터 조회
     * @returns {@link MessageRow} 배열 (최신순 정렬, 최대 100개)
     */
    findMessages(channelId: number, before?: string, parentMessageId?: string): Promise<MessageRow[]> {
        const query: Record<string, unknown> = { channelId };
        if (before) {
            query['_id'] = { $lt: new Types.ObjectId(before) };
        }
        if (parentMessageId) {
            query['parentMessageId'] = new Types.ObjectId(parentMessageId);
        }

        const lookupStages = parentMessageId
            ? [{ $addFields: { mentionedMessage: null } }]
            : [
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
            ];

        return this.messageModel.aggregate([
            { $match: query },
            { $sort: { _id: -1 } },
            { $limit: MESSAGE_FETCH_LIMIT },
            ...lookupStages,
        ]);
    }

    /**
     * ObjectId로 메시지 도큐먼트를 조회합니다.
     *
     * @param messageId - 조회할 메시지의 ObjectId 문자열
     * @returns Mongoose 도큐먼트. 존재하지 않으면 `null`
     */
    findById(messageId: string): Promise<MessageDocument | null> {
        return this.messageModel.findById(messageId);
    }

    /**
     * messageId와 channelId를 함께 사용하여 메시지를 조회합니다.
     *
     * 채널 귀속 검증이 필요한 경우(예: 권한 확인)에 사용하며,
     * 다른 채널의 메시지가 우연히 반환되는 것을 방지합니다.
     *
     * @param messageId - 조회할 메시지의 ObjectId 문자열
     * @param channelId - 메시지가 속해야 하는 채널의 식별자
     * @returns Mongoose 도큐먼트. 존재하지 않거나 채널이 불일치하면 `null`
     */
    findByIdAndChannelId(messageId: string, channelId: number): Promise<MessageDocument | null> {
        return this.messageModel.findOne({ _id: messageId, channelId });
    }

    /**
     * 특정 메시지를 삭제합니다.
     *
     * @param messageId - 삭제할 메시지의 ObjectId 문자열
     * @returns Mongoose `deleteOne` 결과 객체
     */
    deleteById(messageId: string) {
        return this.messageModel.deleteOne({ _id: messageId });
    }

    /**
     * 새 메시지 도큐먼트를 생성합니다.
     *
     * @param input - 생성할 메시지의 필드 값 ({@link CreateMessageInput})
     * @returns 생성된 Mongoose 도큐먼트
     */
    createMessage(input: CreateMessageInput) {
        return this.messageModel.create(input);
    }

    /**
     * 시스템 메시지를 생성합니다.
     *
     * 시스템 메시지는 `type: 'SYSTEM'`으로 고정되며, 알림 발송 대상에서 제외하기 위해
     * `notificationStatus`를 초기값인 `'PENDING'` 대신 `'SENT'`로 설정합니다.
     * `clientMessageId`는 `undefined`로 설정하여 멱등성 키 중복 검사를 우회합니다.
     *
     * @param teamId - 시스템 메시지를 게시할 팀의 식별자
     * @param channelId - 시스템 메시지를 게시할 채널의 식별자
     * @param content - 시스템 메시지 본문
     * @param projectId - 채널이 속한 프로젝트 ID. 없으면 `null`
     * @param authorId - 시스템 행위자로 기록할 사용자 ID (예: 채널 생성자)
     * @returns 생성된 Mongoose 도큐먼트
     */
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

    /**
     * 채널의 파일 첨부 목록을 커서 기반 페이지네이션으로 조회합니다.
     *
     * 내부적으로 `$unwind`로 `attachments` 배열을 펼쳐 각 첨부 파일을 개별 행으로 만들고,
     * `(createdAt DESC, _id DESC, attachmentIndex DESC)` 순으로 정렬합니다.
     *
     * 커서(`before`)는 `{ uploadedAt, messageId, attachmentIndex }` 객체를
     * JSON으로 직렬화한 후 base64로 인코딩한 문자열입니다.
     * 복합 커서를 사용하므로 `createdAt`이 동일한 메시지 내 여러 첨부도 정확하게 페이지네이션합니다.
     *
     * `limit`는 1 이상 {@link FILE_ATTACHMENT_LIMIT} 이하로 클램핑됩니다.
     * 내부적으로 `limit + 1`개를 조회하여 다음 페이지 존재 여부를 판별합니다.
     *
     * @param channelId - 파일 목록을 조회할 채널의 식별자
     * @param before - 이 커서 이전 항목을 조회하는 base64 인코딩 커서. 생략 시 처음부터 조회
     * @param limit - 한 페이지에 반환할 최대 항목 수
     * @returns `items`: {@link FileAttachmentRow} 배열, `nextCursor`: 다음 페이지 커서 (마지막 페이지이면 `null`)
     */
    async findFileAttachments(
        channelId: number,
        before: string | undefined,
        limit: number,
    ): Promise<{ items: FileAttachmentRow[]; nextCursor: string | null }> {
        const safeLimit = Math.min(Math.max(limit, 1), FILE_ATTACHMENT_LIMIT);
        const cursorMatch = before ? this.buildFileCursorMatch(before) : null;

        const pipeline: PipelineStage[] = [
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

        const rows = await this.messageModel.aggregate<FileAttachmentAggregateRow>(pipeline);
        const hasNext = rows.length > safeLimit;
        const pageRows = rows.slice(0, safeLimit);
        const items: FileAttachmentRow[] = pageRows.map((row) => {
            const attachmentIndex = row.attachmentIndex ?? 0;
            return {
                fileId: this.encodeFileCursor(row)!,
                messageId: row._id.toString(),
                fileName: row.attachment.name,
                fileSize: row.attachment.size,
                fileUrl: row.attachment.url,
                mimeType: row.attachment.mimeType,
                uploaderId: row.authorId,
                uploadedAt: row.createdAt.toISOString(),
                attachmentIndex,
            };
        });

        return {
            items,
            nextCursor: hasNext && pageRows.length > 0
                ? this.encodeFileCursor(pageRows.at(-1))
                : null,
        };
    }

    /**
     * `PENDING` 상태인 메시지 하나를 원자적으로 `PROCESSING`으로 전환하고 반환합니다.
     *
     * 아웃박스(outbox) 패턴에서 알림 워커가 중복 처리 없이 하나의 메시지를 점유하기 위해
     * `findOneAndUpdate`로 조회와 상태 변경을 단일 원자 연산으로 수행합니다.
     *
     * @returns 상태가 `PROCESSING`으로 전환된 {@link NotificationMessage}. 처리할 메시지가 없으면 `null`
     */
    findOnePendingAndMarkProcessing(): Promise<NotificationMessage | null> {
        return this.messageModel.findOneAndUpdate(
            { notificationStatus: 'PENDING' },
            { $set: { notificationStatus: 'PROCESSING', notificationProcessingStartedAt: new Date() } },
            { sort: { createdAt: 1 }, new: true },
        ).lean();
    }

    /**
     * PROCESSING 상태로 전환된 지 `staleThresholdMs` 이상 경과한 메시지를 PENDING으로 되돌립니다.
     *
     * 폴러 프로세스가 크래시하면 메시지가 PROCESSING에 영구 stuck될 수 있습니다.
     * 폴러는 PENDING만 조회하므로 이 회수 없이는 해당 메시지의 알림이 영구 유실됩니다.
     *
     * @param staleThresholdMs - PROCESSING을 stale로 판단하는 경과 시간 (밀리초)
     * @returns 회수된 메시지 수
     */
    async reclaimStaleProcessing(staleThresholdMs: number): Promise<number> {
        const staleBeforeDate = new Date(Date.now() - staleThresholdMs);
        const result = await this.messageModel.updateMany(
            {
                notificationStatus: 'PROCESSING',
                notificationProcessingStartedAt: { $lt: staleBeforeDate },
            },
            { $set: { notificationStatus: 'PENDING', notificationProcessingStartedAt: null } },
        );
        return result.modifiedCount;
    }

    /**
     * 채널의 고정 메시지 목록을 최신순으로 최대 100개 조회합니다.
     *
     * `findMessages`와 동일한 `$lookup` 집계로 부모 메시지를 `mentionedMessage`에 조인합니다.
     *
     * @param channelId - 고정 메시지를 조회할 채널의 식별자
     * @returns 고정된 {@link MessageRow} 배열 (최신순 정렬, 최대 100개)
     */
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

    /**
     * 여러 부모 메시지 ID로 작성자 정보를 일괄 조회하여 Map으로 반환합니다.
     *
     * 스레드 알림 발송 시 부모 메시지 작성자를 효율적으로 조회하기 위해 사용합니다.
     *
     * @param parentIds - 조회할 부모 메시지의 ObjectId 배열
     * @returns ObjectId 문자열을 키, `{ authorId }` 객체를 값으로 하는 Map
     */
    async findParentAuthorsByIds(parentIds: Types.ObjectId[]): Promise<Map<string, { authorId: number }>> {
        const parents = await this.messageModel
            .find({ _id: { $in: parentIds } })
            .select('authorId')
            .lean() as { _id: Types.ObjectId; authorId: number }[];

        return new Map(parents.map((parent) => [parent._id.toString(), { authorId: parent.authorId }]));
    }

    /**
     * 메시지에 이모지 반응을 추가합니다.
     *
     * 동시 요청 경합을 처리하기 위해 최대 3단계의 upsert를 순차적으로 시도합니다.
     * 1. 이미 동일 이모지 항목이 존재하고 사용자가 미반응인 경우: `$addToSet`으로 userId 추가
     * 2. 이미 반응한 경우(`alreadyReacted` 확인): `-1` 즉시 반환
     * 3. 이모지 항목이 없는 경우: `$push`로 새 reaction 항목 생성
     * 4. 동시 요청으로 항목이 생성된 경우: 1번 로직 재시도
     * 5. 재시도 후에도 실패하면 메시지 존재 여부를 확인하여 `-1` 또는 `null` 반환
     *
     * @param channelId - 메시지가 속한 채널의 식별자 (채널 귀속 검증용)
     * @param messageId - 반응을 추가할 메시지의 ObjectId 문자열
     * @param emoji - 추가할 이모지 문자열
     * @param userId - 반응하는 사용자의 식별자
     * @returns
     *   - `number (≥ 0)`: 반응 추가 후 해당 이모지의 총 반응 수
     *   - `-1`: 이미 동일 이모지로 반응한 경우
     *   - `null`: 메시지가 존재하지 않는 경우
     */
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

    /**
     * 메시지에서 이모지 반응을 제거합니다.
     *
     * `$pull`로 `userIds`에서 해당 사용자를 제거한 후, `userIds`가 빈 배열이 되면
     * 해당 reaction 항목 자체를 `$pull`로 삭제하여 불필요한 빈 항목을 정리합니다.
     *
     * @param channelId - 메시지가 속한 채널의 식별자 (채널 귀속 검증용)
     * @param messageId - 반응을 제거할 메시지의 ObjectId 문자열
     * @param emoji - 제거할 이모지 문자열
     * @param userId - 반응을 취소하는 사용자의 식별자
     * @returns
     *   - `number (≥ 0)`: 반응 제거 후 해당 이모지의 남은 반응 수
     *   - `-1`: 해당 이모지로 반응한 기록이 없는 경우
     *   - `null`: 메시지가 존재하지 않는 경우
     */
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

    /**
     * 메시지의 알림 상태를 업데이트합니다.
     *
     * `notificationRetryCount`가 전달된 경우 재시도 횟수도 함께 업데이트합니다.
     * 재시도 횟수 없이 상태만 변경하려면 해당 인수를 생략합니다.
     *
     * @param messageId - 업데이트할 메시지의 ObjectId
     * @param notificationStatus - 새로운 알림 상태 (`'PENDING'` | `'PROCESSING'` | `'SENT'` | `'FAILED'`)
     * @param notificationRetryCount - 업데이트할 재시도 횟수. 생략 시 현재 값을 유지
     * @returns Mongoose `updateOne` 결과 객체
     */
    countUnread(channelId: number, afterId: Types.ObjectId | null): Promise<number> {
        const filter: Record<string, unknown> = { channelId, parentMessageId: null };
        if (afterId) {
            filter['_id'] = { $gt: afterId };
        }
        return this.messageModel.countDocuments(filter);
    }

    /**
     * 여러 채널의 최신 메시지를 한 번의 집계로 조회합니다.
     * DM 대화 목록의 미리보기·정렬에 사용합니다.
     *
     * @returns channelId → 최신 메시지 요약 매핑
     */
    async findLastMessages(channelIds: number[]): Promise<Map<number, {
        messageId: string;
        authorId: number;
        content: string;
        type: string;
        createdAt: Date;
    }>> {
        if (channelIds.length === 0) return new Map();
        const rows = await this.messageModel.aggregate<{ _id: number; doc: Message & { _id: Types.ObjectId; createdAt: Date } }>([
            { $match: { channelId: { $in: channelIds } } },
            { $sort: { channelId: 1, _id: -1 } },
            { $group: { _id: '$channelId', doc: { $first: '$$ROOT' } } },
        ]);
        return new Map(rows.map((row) => [row._id, {
            messageId: row.doc._id.toString(),
            authorId: row.doc.authorId,
            content: row.doc.content,
            type: row.doc.type,
            createdAt: row.doc.createdAt,
        }]));
    }

    async countUnreadForChannels(
        memberships: Array<{ channelId: number; lastReadMessageId: Types.ObjectId | null }>,
    ): Promise<Map<number, number>> {
        if (memberships.length === 0) {
            return new Map();
        }
        const orConditions = memberships.map(({ channelId, lastReadMessageId }) => {
            const cond: Record<string, unknown> = { channelId, parentMessageId: null };
            if (lastReadMessageId) {
                cond['_id'] = { $gt: lastReadMessageId };
            }
            return cond;
        });
        const results = await this.messageModel.aggregate<{ _id: number; count: number }>([
            { $match: { $or: orConditions } },
            { $group: { _id: '$channelId', count: { $sum: 1 } } },
        ]);
        const countMap = new Map<number, number>();
        for (const row of results) {
            countMap.set(row._id, row.count);
        }
        return countMap;
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

    /**
     * 파일 커서 문자열을 디코딩하여 MongoDB 집계에서 사용할 `$match` 조건을 생성합니다.
     *
     * `(createdAt, _id, attachmentIndex)` 복합 커서를 사용하여
     * 커서 이전의 항목만 반환하는 `$or` 조건을 구성합니다.
     *
     * @param before - base64 인코딩된 커서 문자열
     * @returns MongoDB `$match` 조건 객체. 커서가 유효하지 않으면 `null`
     */
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

    /**
     * 집계 결과 행을 base64 인코딩된 커서 문자열로 변환합니다.
     *
     * 커서 구성 요소: `{ uploadedAt: ISO8601, messageId: ObjectId 문자열, attachmentIndex: number }`
     * 이를 JSON 직렬화 후 base64 인코딩하여 클라이언트에 불투명(opaque) 커서로 전달합니다.
     *
     * @param row - 집계 결과의 단일 행 (`_id`, `createdAt`, `attachmentIndex` 필드 필요)
     * @returns base64 인코딩된 커서 문자열. `row`가 falsy이면 `null`
     */
    private encodeFileCursor(row: FileAttachmentAggregateRow | undefined): string | null {
        if (!row) {
            return null;
        }

        return Buffer.from(JSON.stringify({
            uploadedAt: row.createdAt.toISOString(),
            messageId: row._id.toString(),
            attachmentIndex: row.attachmentIndex ?? 0,
        })).toString('base64url');
    }

    /**
     * base64 인코딩된 커서 문자열을 파싱하여 커서 객체로 반환합니다.
     *
     * 파싱 실패, 타입 불일치, 잘못된 날짜 형식, 유효하지 않은 ObjectId 등
     * 모든 유효하지 않은 입력에 대해 예외를 던지지 않고 `null`을 반환합니다.
     *
     * @param before - base64 인코딩된 커서 문자열
     * @returns 파싱된 커서 객체 `{ uploadedAt, messageId, attachmentIndex }`. 유효하지 않으면 `null`
     */
    private decodeFileCursor(before: string): { uploadedAt: string; messageId: string; attachmentIndex: number } | null {
        try {
            const parsed = JSON.parse(Buffer.from(before, 'base64url').toString('utf8')) as {
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
