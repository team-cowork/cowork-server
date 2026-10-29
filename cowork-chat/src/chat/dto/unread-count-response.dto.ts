import {ApiProperty as apiProperty} from '@nestjs/swagger';

export class UnreadCountItemDto {
	@apiProperty() channelId!: number;
	@apiProperty() unreadCount!: number;
}
