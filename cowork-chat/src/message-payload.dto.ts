import { IsString } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

export class MessagePayload {
    @ApiProperty({ description: '메시지를 전송할 채널 ID', example: '42' })
    @IsString()
    channelId!: string;

    @ApiProperty({ description: '메시지 내용', example: '안녕하세요!' })
    @IsString()
    content!: string;
}
