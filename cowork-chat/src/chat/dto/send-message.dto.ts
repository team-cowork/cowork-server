import {
    IsArray,
    IsEnum,
    IsMongoId,
    IsOptional,
    IsString,
    MaxLength,
    ValidateNested,
} from 'class-validator';
import { Type } from 'class-transformer';
import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';

export class AttachmentDto {
    @ApiProperty({ description: '업로드 완료 확인에서 반환한 최종 object key' })
    @IsString() objectKey!: string;
}

export class SendMessageDto {
    @ApiProperty({ description: '메시지 내용 (최대 25000자). 멘션은 `<@userId>` 형식으로 포함 가능 (예: `<@123>`)', maxLength: 25_000, minLength: 1 })
    @IsString() @MaxLength(25_000) content!: string;

    @ApiPropertyOptional({ enum: ['TEXT', 'FILE'], default: 'TEXT' })
    @IsEnum(['TEXT', 'FILE']) @IsOptional() type?: string;

    @ApiPropertyOptional({ type: [AttachmentDto], description: 'S3 첨부파일 목록' })
    @IsArray() @IsOptional() @ValidateNested({ each: true }) @Type(() => AttachmentDto) attachments?: AttachmentDto[];

    @ApiPropertyOptional({ description: '답장 대상 메시지 ID (MongoDB ObjectId)' })
    @IsMongoId() @IsOptional() parentMessageId?: string;

    @ApiPropertyOptional({ description: '클라이언트 생성 멱등성 키 (UUID 권장)' })
    @IsString() @IsOptional() clientMessageId?: string;
}
