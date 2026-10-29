import {IsIn as isIn, IsMongoId as isMongoId, IsOptional as isOptional} from 'class-validator';
import {ApiProperty as apiProperty, ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';
import {CHAT_MESSAGE_QUARANTINE_STATUSES, type ChatMessageQuarantineStatus} from '../schema/index.js';

export class ChatMessageQuarantineRecordIdDto {
	@apiProperty({description: '격리 레코드 ID'})
	@isMongoId() id!: string;
}

export class FindChatMessageQuarantineRecordsDto {
	@apiPropertyOptional({enum: CHAT_MESSAGE_QUARANTINE_STATUSES, description: '이 상태의 레코드만 조회'})
	@isIn(CHAT_MESSAGE_QUARANTINE_STATUSES) @isOptional() status?: ChatMessageQuarantineStatus;
}
