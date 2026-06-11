import { IsNumber, IsOptional, IsString, MaxLength, IsEnum, IsArray, IsMongoId } from 'class-validator';
import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';

export class AttachmentDto {
    @ApiProperty({ description: 'S3 파일명' })
    @IsString() name!: string;

    @ApiProperty({ description: 'S3 URL' })
    @IsString() url!: string;

    @ApiProperty({ description: '파일 크기 (bytes)' })
    @IsNumber() size!: number;

    @ApiProperty({ description: 'MIME 타입', example: 'image/png' })
    @IsString() mimeType!: string;
}

export class SendMessageDto {
    @ApiPropertyOptional({ description: '팀 ID (DM 채널 메시지는 생략, 서버에서 null 처리)', nullable: true })
    @IsNumber() @IsOptional() teamId?: number | null;

    @ApiPropertyOptional({ description: '프로젝트 ID (팀 채널이면 null)', nullable: true })
    @IsNumber() @IsOptional() projectId?: number | null;

    @ApiProperty({ description: '메시지 내용 (최대 25000자). 멘션은 `<@userId>` 형식으로 포함 가능 (예: `<@123>`)', maxLength: 25000, minLength: 1 })
    @IsString() @MaxLength(25000) content!: string;

    @ApiPropertyOptional({ enum: ['TEXT', 'FILE'], default: 'TEXT' })
    @IsEnum(['TEXT', 'FILE']) @IsOptional() type?: string;

    @ApiPropertyOptional({ type: [AttachmentDto], description: 'S3 첨부파일 목록' })
    @IsArray() @IsOptional() attachments?: AttachmentDto[];

    @ApiPropertyOptional({ description: '답장 대상 메시지 ID (MongoDB ObjectId)' })
    @IsMongoId() @IsOptional() parentMessageId?: string;

    @ApiPropertyOptional({ description: '클라이언트 생성 멱등성 키 (UUID 권장)' })
    @IsString() @IsOptional() clientMessageId?: string;
}
