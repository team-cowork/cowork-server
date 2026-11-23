import {
	ArrayMaxSize as arrayMaxSize,
	IsArray as isArray,
	IsEnum as isEnum,
	IsISO8601 as isIso8601,
	IsOptional as isOptional,
	IsString as isString,
	Matches as matches,
	MaxLength as maxLength,
	ValidateNested as validateNested,
} from 'class-validator';
import {Type as transformType} from 'class-transformer';
import {ApiProperty as apiProperty, ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';
import {type Nullable} from '../../common/type/nullable.js';
import {AttachmentDto} from '../dto/send-message.dto.js';
import {AttachmentResponseDto} from '../dto/message-response.dto.js';
import {SYSTEM_MESSAGE_KEY_PREFIX} from '../kafka/system-message-key.js';
import {
	SCHEDULED_MESSAGE_FAILURE_REASONS,
	SCHEDULED_MESSAGE_STATUSES,
	type ScheduledMessageFailureReason,
	type ScheduledMessageStatus,
} from './scheduled-message.schema.js';

const SCHEDULED_AT_DESCRIPTION = '발송 시각입니다. 지금부터 1분 뒤에서 120일 뒤 사이여야 하고, Z나 +09:00 같은 시간대 표기가 있어야 합니다.';
// 시간대가 없는 값은 서버 시간대로 해석되므로 받지 않는다.
// 형식은 `isIso8601`이 검사하므로 끝의 시간대 표기만 확인한다. 날짜만 있는 값은 이 표기로 끝날 수 없다.
const ZONED_DATE_TIME = /(?:z|[+\-]\d{2}:?\d{2})$/iv;
const ZONED_DATE_TIME_MESSAGE = 'scheduledAt must include a time and a time zone offset';

export class CreateScheduledMessageDto {
	@apiProperty({description: '메시지 내용입니다. 첨부가 없으면 공백만 보낼 수 없습니다.', maxLength: 25_000})
	@isString() @maxLength(25_000) content!: string;

	@apiPropertyOptional({enum: ['TEXT', 'FILE'], default: 'TEXT'})
	@isEnum(['TEXT', 'FILE']) @isOptional() type?: string;

	@apiPropertyOptional({type: [AttachmentDto], maxItems: 10, description: '업로드 완료 확인에서 받은 첨부 목록입니다. file_attach 권한이 필요합니다.'})
	@isArray() @isOptional() @arrayMaxSize(10) @validateNested({each: true}) @transformType(() => AttachmentDto) attachments?: AttachmentDto[];

	@apiProperty({format: 'date-time', description: SCHEDULED_AT_DESCRIPTION})
	@isIso8601({strict: true}) @matches(ZONED_DATE_TIME, {message: ZONED_DATE_TIME_MESSAGE}) scheduledAt!: string;

	@apiPropertyOptional({description: '재시도 중복 방지 키입니다. 첨부가 있으면 필요합니다. UUID를 권장합니다.', maxLength: 200})
	@isString() @maxLength(200) @isOptional()
	@matches(new RegExp(`^(?!${SYSTEM_MESSAGE_KEY_PREFIX})`, 'v'), {message: `clientMessageId must not start with ${SYSTEM_MESSAGE_KEY_PREFIX}`})
	clientMessageId?: string;
}

export class UpdateScheduledMessageDto {
	@apiPropertyOptional({description: '바꿀 메시지 내용입니다. 첨부가 없으면 공백만 보낼 수 없습니다.', maxLength: 25_000})
	@isString() @maxLength(25_000) @isOptional() content?: string;

	@apiPropertyOptional({format: 'date-time', description: SCHEDULED_AT_DESCRIPTION})
	@isIso8601({strict: true}) @matches(ZONED_DATE_TIME, {message: ZONED_DATE_TIME_MESSAGE}) @isOptional() scheduledAt?: string;
}

export class ScheduledMessageResponseDto {
	@apiProperty() id!: string;
	@apiProperty() channelId!: number;
	@apiProperty() content!: string;
	@apiProperty({enum: ['TEXT', 'FILE']}) type!: string;
	@apiProperty({type: [AttachmentResponseDto]}) attachments!: AttachmentResponseDto[];
	@apiProperty({format: 'date-time'}) scheduledAt!: string;
	@apiProperty({enum: SCHEDULED_MESSAGE_STATUSES, description: 'DISPATCHING은 발송 중이므로 수정하거나 취소할 수 없습니다.'}) status!: ScheduledMessageStatus;
	@apiProperty({enum: SCHEDULED_MESSAGE_FAILURE_REASONS, nullable: true, description: '발송하지 못한 사유입니다. FAILED가 아니면 null입니다.'})
	failureReason!: Nullable<ScheduledMessageFailureReason>;

	@apiProperty({format: 'date-time'}) createdAt!: string;
}
