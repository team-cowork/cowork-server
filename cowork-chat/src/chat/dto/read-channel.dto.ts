import { IsMongoId } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

export class ReadChannelDto {
    @ApiProperty({ description: '마지막으로 읽은 메시지 ObjectId' })
    @IsMongoId()
    lastReadMessageId!: string;
}
