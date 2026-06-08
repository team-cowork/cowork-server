import { IsInt, IsPositive } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

export class OpenConversationDto {
    @ApiProperty({ description: 'DM을 열 상대방 userId' })
    @IsInt()
    @IsPositive()
    targetUserId!: number;
}
