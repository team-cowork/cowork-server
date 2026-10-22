import {IsIn, IsMongoId, IsOptional} from 'class-validator';
import {ApiProperty, ApiPropertyOptional} from '@nestjs/swagger';
import {CHAT_MESSAGE_QUARANTINE_STATUSES, type ChatMessageQuarantineStatus} from '../schema';

export class ChatMessageQuarantineRecordIdDto {
    @ApiProperty({description: '격리 레코드 ID'})
    @IsMongoId() id!: string;
}

export class FindChatMessageQuarantineRecordsDto {
    @ApiPropertyOptional({enum: CHAT_MESSAGE_QUARANTINE_STATUSES, description: '이 상태의 레코드만 조회'})
    @IsIn(CHAT_MESSAGE_QUARANTINE_STATUSES) @IsOptional() status?: ChatMessageQuarantineStatus;
}
