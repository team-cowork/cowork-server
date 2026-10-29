import {
	IsArray as isArray,
	IsEnum as isEnum,
	IsMongoId as isMongoId,
	IsOptional as isOptional,
	IsString as isString,
	MaxLength as maxLength,
	ValidateNested as validateNested,
} from 'class-validator';
import {Type as transformType} from 'class-transformer';
import {ApiProperty as apiProperty, ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';

export class AttachmentDto {
	@apiProperty({description: '업로드 완료 확인에서 반환한 최종 object key'})
	@isString() objectKey!: string;
}

export class SendMessageDto {
	@apiProperty({description: '메시지 내용 (최대 25000자). 멘션은 `<@userId>` 형식으로 포함 가능 (예: `<@123>`)', maxLength: 25_000, minLength: 1})
	@isString() @maxLength(25_000) content!: string;

	@apiPropertyOptional({enum: ['TEXT', 'FILE'], default: 'TEXT'})
	@isEnum(['TEXT', 'FILE']) @isOptional() type?: string;

	@apiPropertyOptional({type: [AttachmentDto], description: 'S3 첨부파일 목록'})
	@isArray() @isOptional() @validateNested({each: true}) @transformType(() => AttachmentDto) attachments?: AttachmentDto[];

	@apiPropertyOptional({description: '답장 대상 메시지 ID (MongoDB ObjectId)'})
	@isMongoId() @isOptional() parentMessageId?: string;

	@apiPropertyOptional({description: '클라이언트 생성 멱등성 키 (UUID 권장)'})
	@isString() @isOptional() clientMessageId?: string;
}
