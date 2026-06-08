import { IsArray, IsEnum, IsOptional, IsString, MaxLength, ValidateNested } from 'class-validator';
import { Type } from 'class-transformer';
import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';

export class AttachmentDto {
    @ApiProperty({ example: 'photo.png', description: '파일 이름' })
    @IsString()
    name!: string;

    @ApiProperty({ example: 'https://minio.example.com/dm/photo.png', description: '파일 URL' })
    @IsString()
    url!: string;

    @ApiProperty({ example: 'image/png', description: 'MIME 타입' })
    @IsString()
    mimeType!: string;

    @ApiProperty({ example: 204800, description: '파일 크기 (bytes)' })
    size!: number;
}

export class SendMessageDto {
    @ApiProperty({ example: '안녕하세요!', description: '메시지 내용 (최대 25,000자)' })
    @IsString()
    @MaxLength(25000)
    content!: string;

    @ApiPropertyOptional({ enum: ['TEXT', 'FILE'], default: 'TEXT', description: '메시지 타입 (생략 시 TEXT)' })
    @IsOptional()
    @IsEnum(['TEXT', 'FILE'])
    type?: string;

    @ApiPropertyOptional({ type: [AttachmentDto], description: '첨부 파일 목록 (type=FILE 일 때 사용)' })
    @IsOptional()
    @IsArray()
    @ValidateNested({ each: true })
    @Type(() => AttachmentDto)
    attachments?: AttachmentDto[];

    @ApiPropertyOptional({ example: 'uuid-v4-string', description: '클라이언트 중복 전송 방지용 멱등성 키' })
    @IsOptional()
    @IsString()
    clientMessageId?: string;

    @ApiPropertyOptional({ type: [Number], example: [7, 13], description: '@멘션할 사용자 ID 목록' })
    @IsOptional()
    @IsArray()
    mentions?: number[];
}
