import {ApiProperty, ApiPropertyOptional} from '@nestjs/swagger';
import {WebhookAuthorResponseDto} from './message-response.dto';

export class SearchMessageItemDto {
    @ApiProperty() messageId!: string;
    @ApiProperty() channelId!: number;
    @ApiProperty({type: Number, nullable: true}) authorId!: number | null;
    @ApiPropertyOptional({type: WebhookAuthorResponseDto}) webhook?: WebhookAuthorResponseDto;
    @ApiProperty() content!: string;
    @ApiProperty({type: [String], description: '<em>키워드</em> 포함 snippet 배열'}) highlight!: string[];
    @ApiProperty({enum: ['TEXT', 'FILE', 'SYSTEM']}) type!: string;
    @ApiProperty() hasAttachments!: boolean;
    @ApiProperty() isPinned!: boolean;
    @ApiProperty() createdAt!: string;
}

export class SearchMessagesResponseDto {
    @ApiProperty({type: [SearchMessageItemDto]}) messages!: SearchMessageItemDto[];
    @ApiPropertyOptional({nullable: true, description: '다음 페이지 커서 (마지막 페이지이면 null)'}) nextCursor!: string | null;
}
