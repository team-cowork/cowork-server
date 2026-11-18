import {ApiProperty as apiProperty, ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';
import {type Nullable} from '../../common/type/nullable.js';

export class WebhookAuthorResponseDto {
	@apiProperty() id!: number;
	@apiProperty() name!: string;
	@apiProperty({type: String, nullable: true}) avatarUrl!: Nullable<string>;
}

class ReactionResponseDto {
	@apiProperty() emoji!: string;
	@apiProperty() count!: number;
	@apiProperty() myReaction!: boolean;
}

export class AttachmentResponseDto {
	@apiProperty() name!: string;
	@apiPropertyOptional() objectKey?: string;
	@apiProperty() size!: number;
	@apiProperty() mimeType!: string;
	@apiProperty({description: '스포일러 표시 여부. 이 값이 없던 기존 메시지는 false', default: false}) spoiler!: boolean;
}

export class MentionedMessageDto {
	@apiProperty({description: '부모 메시지 MongoDB ObjectId'}) _id!: string;
	@apiProperty({type: Number, description: '부모 메시지 작성자 ID', nullable: true}) authorId!: Nullable<number>;
	@apiPropertyOptional({type: WebhookAuthorResponseDto}) webhook?: WebhookAuthorResponseDto;
	@apiProperty({description: '부모 메시지 내용'}) content!: string;
	@apiProperty({enum: ['TEXT', 'FILE', 'SYSTEM'], description: '부모 메시지 타입'}) type!: string;
	@apiProperty({description: '부모 메시지 생성 시각 (ISO 8601)'}) createdAt!: string;
}

export class MessageResponseDto {
	@apiProperty() _id!: string;
	@apiProperty() teamId!: number;
	@apiPropertyOptional({nullable: true}) projectId!: Nullable<number>;
	@apiProperty() channelId!: number;
	@apiProperty({type: Number, nullable: true}) authorId!: Nullable<number>;
	@apiPropertyOptional({type: WebhookAuthorResponseDto}) webhook?: WebhookAuthorResponseDto;
	@apiProperty() content!: string;
	@apiProperty({enum: ['TEXT', 'FILE', 'SYSTEM']}) type!: string;
	@apiProperty({type: [AttachmentResponseDto]}) attachments!: AttachmentResponseDto[];
	@apiProperty({nullable: true, description: '답장 원본 메시지 ObjectId (답장이 아니면 null)'}) parentMessageId!: Nullable<string>;
	@apiProperty({type: Number, nullable: true, description: '메시지가 속한 스레드 ID (채널 메시지이면 null)'}) threadId!: Nullable<number>;
	@apiProperty() isEdited!: boolean;
	@apiProperty() isPinned!: boolean;
	@apiPropertyOptional({nullable: true}) clientMessageId!: Nullable<string>;
	@apiProperty({type: [Number], description: '메시지 내용에서 파싱된 멘션 사용자 ID 목록 (`<@userId>` 형식으로 작성된 멘션)'}) mentions!: number[];
	@apiProperty() createdAt!: string;
	@apiProperty() updatedAt!: string;
	@apiProperty({type: [ReactionResponseDto]}) reactions!: ReactionResponseDto[];
	@apiPropertyOptional({type: MentionedMessageDto, nullable: true, description: '답장 원본 메시지 정보 (답장이 아니면 null)'}) mentionedMessage?: Nullable<MentionedMessageDto>;
}

export class DeleteMessageResponseDto {
	@apiProperty() channelId!: number;
	@apiProperty() messageId!: string;
}

export class SendMessageResponseDto {
	@apiProperty() queued!: boolean;
}
