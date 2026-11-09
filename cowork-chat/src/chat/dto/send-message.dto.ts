import {
	ArrayMaxSize as arrayMaxSize,
	IsArray as isArray,
	IsEnum as isEnum,
	IsMongoId as isMongoId,
	IsOptional as isOptional,
	IsString as isString,
	Matches as matches,
	MaxLength as maxLength,
	ValidateNested as validateNested,
} from 'class-validator';
import {Type as transformType} from 'class-transformer';
import {ApiProperty as apiProperty, ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';
import {SYSTEM_MESSAGE_KEY_PREFIX} from '../kafka/system-message-key.js';

export class AttachmentDto {
	@apiProperty({description: '업로드 완료 확인에서 반환한 최종 object key'})
	@isString() @maxLength(200) objectKey!: string;
}

export class SendMessageDto {
	@apiProperty({description: '메시지 내용 (최대 25000자). 멘션은 `<@userId>` 형식으로 포함 가능 (예: `<@123>`)', maxLength: 25_000, minLength: 1})
	@isString() @maxLength(25_000) content!: string;

	@apiPropertyOptional({enum: ['TEXT', 'FILE'], default: 'TEXT'})
	@isEnum(['TEXT', 'FILE']) @isOptional() type?: string;

	@apiPropertyOptional({type: [AttachmentDto], description: 'S3 첨부파일 목록'})
	@isArray() @isOptional() @arrayMaxSize(10) @validateNested({each: true}) @transformType(() => AttachmentDto) attachments?: AttachmentDto[];

	@apiPropertyOptional({description: '답장 대상 메시지 ID (MongoDB ObjectId)'})
	@isMongoId() @isOptional() parentMessageId?: string;

	// 시스템 메시지 키를 미리 차지해 다른 채널의 시스템 메시지 저장을 막지 못하게 접두사를 거부한다.
	@apiPropertyOptional({description: `클라이언트 생성 멱등성 키 (UUID 권장, \`${SYSTEM_MESSAGE_KEY_PREFIX}\`로 시작할 수 없음)`})
	@isString() @maxLength(200) @isOptional()
	@matches(new RegExp(`^(?!${SYSTEM_MESSAGE_KEY_PREFIX})`, 'v'), {message: `clientMessageId must not start with ${SYSTEM_MESSAGE_KEY_PREFIX}`})
	clientMessageId?: string;
}
