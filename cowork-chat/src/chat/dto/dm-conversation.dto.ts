import {ApiProperty as apiProperty, ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';
import {type Nullable} from '../../common/type/nullable.js';
import {WebhookAuthorResponseDto} from './message-response.dto.js';

class DmLastMessageDto {
	@apiProperty() messageId!: string;
	@apiProperty({type: Number, nullable: true}) authorId!: Nullable<number>;
	@apiPropertyOptional({type: WebhookAuthorResponseDto}) webhook?: WebhookAuthorResponseDto;
	@apiProperty() content!: string;
	@apiProperty() type!: string;
	@apiProperty({type: String, format: 'date-time'}) createdAt!: string;
}

export class DmConversationDto {
	@apiProperty() channelId!: number;
	@apiProperty({type: Number, nullable: true}) otherUserId!: Nullable<number>;
	@apiProperty() unreadCount!: number;
	@apiProperty({type: DmLastMessageDto, nullable: true}) lastMessage!: Nullable<DmLastMessageDto>;
}
