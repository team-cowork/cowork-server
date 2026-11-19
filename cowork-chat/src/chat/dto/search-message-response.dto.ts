import {ApiProperty as apiProperty, ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';
import {type Nullable} from '../../common/type/nullable.js';
import {AttachmentResponseDto, WebhookAuthorResponseDto} from './message-response.dto.js';

export class SearchMessageItemDto {
	@apiProperty() messageId!: string;
	@apiProperty() channelId!: number;
	@apiProperty({type: Number, nullable: true}) authorId!: Nullable<number>;
	@apiPropertyOptional({type: WebhookAuthorResponseDto}) webhook?: WebhookAuthorResponseDto;
	@apiProperty() content!: string;
	@apiProperty({type: [String], description: '<em>키워드</em> 포함 snippet 배열'}) highlight!: string[];
	@apiProperty({enum: ['TEXT', 'FILE', 'SYSTEM']}) type!: string;
	@apiProperty() hasAttachments!: boolean;
	@apiProperty({type: [AttachmentResponseDto], description: '원본 메시지의 첨부 목록. 원본을 찾지 못하면 빈 배열'}) attachments!: AttachmentResponseDto[];
	@apiProperty() isPinned!: boolean;
	@apiProperty() createdAt!: string;
}

export class SearchMessagesResponseDto {
	@apiProperty({type: [SearchMessageItemDto]}) messages!: SearchMessageItemDto[];
	@apiPropertyOptional({nullable: true, description: '다음 페이지 커서 (마지막 페이지이면 null)'}) nextCursor!: Nullable<string>;
}
