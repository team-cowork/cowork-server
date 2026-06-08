import { ApiProperty } from '@nestjs/swagger';
import { Types } from 'mongoose';

type RawMessage = {
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

export class AttachmentResponseDto {
    @ApiProperty({ example: 'photo.png' })
    name!: string;

    @ApiProperty({ example: 'https://minio.example.com/dm/photo.png' })
    url!: string;

    @ApiProperty({ example: 204800, description: '파일 크기 (bytes)' })
    size!: number;

    @ApiProperty({ example: 'image/png' })
    mimeType!: string;
}

export class ReactionResponseDto {
    @ApiProperty({ example: '👍' })
    emoji!: string;

    @ApiProperty({ example: 3, description: '해당 이모지 리액션 수' })
    count!: number;

    @ApiProperty({ example: true, description: '요청한 사용자의 리액션 여부' })
    myReaction!: boolean;
}

export class MessageResponseDto {
    @ApiProperty({ example: '6655f1e2a1b2c3d4e5f60001', description: '메시지 ObjectId' })
    id!: string;

    @ApiProperty({ example: '6655f1e2a1b2c3d4e5f60000', description: '대화방 ObjectId' })
    conversationId!: string;

    @ApiProperty({ example: 42, description: '작성자 사용자 ID' })
    authorId!: number;

    @ApiProperty({ example: '안녕하세요!', description: '메시지 내용 (최대 25,000자)' })
    content!: string;

    @ApiProperty({ enum: ['TEXT', 'FILE', 'SYSTEM'], example: 'TEXT' })
    type!: string;

    @ApiProperty({ type: [AttachmentResponseDto] })
    attachments!: AttachmentResponseDto[];

    @ApiProperty({ example: false, description: '수정된 메시지 여부' })
    isEdited!: boolean;

    @ApiProperty({ type: [Number], example: [7, 13], description: '@멘션된 사용자 ID 목록' })
    mentions!: number[];

    @ApiProperty({ type: [ReactionResponseDto] })
    reactions!: ReactionResponseDto[];

    @ApiProperty({ example: '2026-06-08T10:00:00.000Z', description: '생성 시각 (ISO 8601)' })
    createdAt!: string;

    @ApiProperty({ example: '2026-06-08T10:05:00.000Z', description: '수정 시각 (ISO 8601)' })
    updatedAt!: string;

    constructor(msg: RawMessage, viewerUserId?: number) {
        this.id = msg._id.toString();
        this.conversationId = msg.conversationId.toString();
        this.authorId = msg.authorId;
        this.content = msg.content;
        this.type = msg.type;
        this.attachments = msg.attachments ?? [];
        this.isEdited = msg.isEdited;
        this.mentions = msg.mentions ?? [];
        this.reactions = (msg.reactions ?? []).map((r) => ({
            emoji: r.emoji,
            count: r.userIds.length,
            myReaction: viewerUserId !== undefined ? r.userIds.includes(viewerUserId) : false,
        }));
        this.createdAt = msg.createdAt.toISOString();
        this.updatedAt = msg.updatedAt.toISOString();
    }
}
