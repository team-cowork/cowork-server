import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { DmConversationDocument } from '../schema/dm-conversation.schema';

export class ParticipantDto {
    @ApiProperty({ example: 42, description: '참여자 사용자 ID' })
    userId!: number;

    @ApiProperty({ example: false, description: '숨김 여부 (true 이면 목록에서 숨겨짐)' })
    isHidden!: boolean;

    @ApiProperty({ example: 5, description: '읽지 않은 메시지 수' })
    unreadCount!: number;

    @ApiPropertyOptional({ example: '6655f1e2a1b2c3d4e5f60001', nullable: true, description: '마지막으로 읽은 메시지 ObjectId' })
    lastReadMessageId!: string | null;

    constructor(p: { userId: number; isHidden: boolean; unreadCount: number; lastReadMessageId: any }) {
        this.userId = p.userId;
        this.isHidden = p.isHidden;
        this.unreadCount = p.unreadCount;
        this.lastReadMessageId = p.lastReadMessageId?.toString() ?? null;
    }
}

export class ConversationResponseDto {
    @ApiProperty({ example: '6655f1e2a1b2c3d4e5f60000', description: '대화방 ObjectId' })
    id!: string;

    @ApiProperty({ type: [ParticipantDto] })
    participants!: ParticipantDto[];

    @ApiPropertyOptional({ example: '6655f1e2a1b2c3d4e5f60001', nullable: true, description: '마지막 메시지 ObjectId' })
    lastMessageId!: string | null;

    @ApiPropertyOptional({ example: '2026-06-08T10:05:00.000Z', nullable: true, description: '마지막 메시지 전송 시각 (ISO 8601)' })
    lastMessageAt!: string | null;

    @ApiProperty({ example: '2026-06-08T09:00:00.000Z', description: '대화방 생성 시각 (ISO 8601)' })
    createdAt!: string;

    constructor(doc: DmConversationDocument) {
        this.id = doc._id.toString();
        this.participants = doc.participants.map((p) => new ParticipantDto(p));
        this.lastMessageId = doc.lastMessageId?.toString() ?? null;
        this.lastMessageAt = doc.lastMessageAt?.toISOString() ?? null;
        this.createdAt = doc.createdAt.toISOString();
    }
}
