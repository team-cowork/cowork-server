import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';

class ReactionResponseDto {
    @ApiProperty() emoji!: string;
    @ApiProperty() count!: number;
    @ApiProperty() myReaction!: boolean;
}

class AttachmentResponseDto {
    @ApiProperty() name!: string;
    @ApiProperty() url!: string;
    @ApiProperty() size!: number;
    @ApiProperty() mimeType!: string;
}

class MentionedMessageDto {
    @ApiProperty({ description: '부모 메시지 MongoDB ObjectId' }) _id!: string;
    @ApiProperty({ description: '부모 메시지 작성자 ID' }) authorId!: number;
    @ApiProperty({ description: '부모 메시지 내용' }) content!: string;
    @ApiProperty({ enum: ['TEXT', 'FILE', 'SYSTEM'], description: '부모 메시지 타입' }) type!: string;
    @ApiProperty({ description: '부모 메시지 생성 시각 (ISO 8601)' }) createdAt!: string;
}

export class MessageResponseDto {
    @ApiProperty() _id!: string;
    @ApiProperty() teamId!: number;
    @ApiPropertyOptional({ nullable: true }) projectId!: number | null;
    @ApiProperty() channelId!: number;
    @ApiProperty() authorId!: number;
    @ApiProperty() content!: string;
    @ApiProperty({ enum: ['TEXT', 'FILE', 'SYSTEM'] }) type!: string;
    @ApiProperty({ type: [AttachmentResponseDto] }) attachments!: AttachmentResponseDto[];
    @ApiPropertyOptional({ nullable: true, description: '스레드 답글의 부모 메시지 ObjectId (최상위 메시지이면 null)' }) parentMessageId!: string | null;
    @ApiProperty() isEdited!: boolean;
    @ApiProperty() isPinned!: boolean;
    @ApiPropertyOptional({ nullable: true }) clientMessageId!: string | null;
    @ApiProperty({ type: [Number], description: '메시지 내용에서 파싱된 멘션 사용자 ID 목록 (`<@userId>` 형식으로 작성된 멘션)' }) mentions!: number[];
    @ApiProperty() createdAt!: string;
    @ApiProperty() updatedAt!: string;
    @ApiProperty({ type: [ReactionResponseDto] }) reactions!: ReactionResponseDto[];
    @ApiPropertyOptional({ type: MentionedMessageDto, nullable: true, description: '스레드 답글의 부모 메시지 정보 (최상위 메시지이거나 스레드가 아니면 null)' }) mentionedMessage?: MentionedMessageDto | null;
}

export class MessageListResponseDto {
    @ApiProperty({ type: [MessageResponseDto] }) messages!: MessageResponseDto[];
}

export class DeleteMessageResponseDto {
    @ApiProperty() channelId!: number;
    @ApiProperty() messageId!: string;
}

export class SendMessageResponseDto {
    @ApiProperty() queued!: boolean;
}
