import {IsMongoId as isMongoId} from 'class-validator';
import {ApiProperty as apiProperty} from '@nestjs/swagger';

export class ReadChannelDto {
	@apiProperty({description: '마지막으로 읽은 메시지 ObjectId'})
	@isMongoId()
	lastReadMessageId!: string;
}
