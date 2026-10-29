import {ApiProperty as apiProperty, ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';
import {type Nullable} from '../../common/type/nullable.js';
import {WebhookAuthorResponseDto} from './message-response.dto.js';

export class SearchMessageItemDto {
	@apiProperty() messageId!: string;
	@apiProperty() channelId!: number;
	@apiProperty({type: Number, nullable: true}) authorId!: Nullable<number>;
	@apiPropertyOptional({type: WebhookAuthorResponseDto}) webhook?: WebhookAuthorResponseDto;
	@apiProperty() content!: string;
	@apiProperty({type: [String], description: '<em>키워드</em> 포함 snippet 배열'}) highlight!: string[];
	@apiProperty({enum: ['TEXT', 'FILE', 'SYSTEM']}) type!: string;
	@apiProperty() hasAttachments!: boolean;
	@apiProperty() isPinned!: boolean;
	@apiProperty() createdAt!: string;
}

export class SearchMessagesResponseDto {
	@apiProperty({type: [SearchMessageItemDto]}) messages!: SearchMessageItemDto[];
	@apiPropertyOptional({nullable: true, description: '다음 페이지 커서 (마지막 페이지이면 null)'}) nextCursor!: Nullable<string>;
}
