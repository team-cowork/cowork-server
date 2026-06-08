import { IsOptional, IsString } from 'class-validator';
import { ApiPropertyOptional } from '@nestjs/swagger';

export class GetMessagesDto {
    @ApiPropertyOptional({ description: '이 ObjectId 이전 메시지를 조회하는 커서' })
    @IsOptional()
    @IsString()
    before?: string;
}
