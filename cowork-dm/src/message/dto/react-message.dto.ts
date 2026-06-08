import { IsString } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

export class ReactMessageDto {
    @ApiProperty({ description: '이모지 문자열 (예: 👍)' })
    @IsString()
    emoji!: string;

    @ApiProperty({ enum: ['ADD', 'REMOVE'] })
    @IsString()
    action!: 'ADD' | 'REMOVE';
}
