import { ApiProperty } from '@nestjs/swagger';

export class UnreadCountItemDto {
    @ApiProperty() channelId!: number;
    @ApiProperty() unreadCount!: number;
}
