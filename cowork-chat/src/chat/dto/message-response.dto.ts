import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';

class AttachmentResponseDto {
    @ApiProperty() name!: string;
    @ApiProperty() url!: string;
    @ApiProperty() size!: number;
    @ApiProperty() mimeType!: string;
}

class MentionedMessageDto {
    @ApiProperty() _id!: string;
    @ApiProperty() authorId!: number;
    @ApiProperty() content!: string;
    @ApiProperty({ enum: ['TEXT', 'FILE', 'SYSTEM'] }) type!: string;
    @ApiProperty() createdAt!: string;
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
    @ApiPropertyOptional({ nullable: true }) parentMessageId!: string | null;
    @ApiProperty() isEdited!: boolean;
    @ApiProperty() isPinned!: boolean;
    @ApiPropertyOptional({ nullable: true }) clientMessageId!: string | null;
    @ApiProperty({ type: [Number] }) mentions!: number[];
    @ApiProperty() createdAt!: string;
    @ApiProperty() updatedAt!: string;
    @ApiPropertyOptional({ type: MentionedMessageDto, nullable: true }) mentionedMessage?: MentionedMessageDto | null;
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
