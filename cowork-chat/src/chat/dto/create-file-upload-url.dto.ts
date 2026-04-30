import { ApiProperty } from '@nestjs/swagger';
import { IsNumber, IsString, MaxLength, Min } from 'class-validator';

export class CreateFileUploadUrlRequestDto {
    @ApiProperty({ description: '원본 파일명', example: 'screenshot.png' })
    @IsString()
    @MaxLength(255)
    filename!: string;

    @ApiProperty({ description: 'MIME 타입', example: 'image/png' })
    @IsString()
    @MaxLength(100)
    contentType!: string;

    @ApiProperty({ description: '파일 크기(bytes)', example: 102400 })
    @IsNumber()
    @Min(1)
    size!: number;
}

export class CreateFileUploadUrlResponseDto {
    @ApiProperty({ description: 'MinIO object key' })
    objectKey!: string;

    @ApiProperty({ description: '클라이언트가 PUT 업로드에 사용할 presigned URL' })
    uploadUrl!: string;

    @ApiProperty({ description: '메시지 attachments.url에 저장할 파일 URL' })
    fileUrl!: string;

    @ApiProperty({ description: 'presigned URL 만료 시간(초)' })
    expiresInSeconds!: number;

    @ApiProperty({ description: 'PUT 요청 시 같이 보낼 헤더' })
    headers!: Record<string, string>;
}

