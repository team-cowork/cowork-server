import { IsOptional, IsMongoId } from 'class-validator';
import { ApiPropertyOptional } from '@nestjs/swagger';

export class GetMessagesDto {
    @ApiPropertyOptional({ description: '이 메시지 ID 이전 메시지 조회 (커서 페이지네이션)' })
    @IsMongoId() @IsOptional() before?: string;
}
