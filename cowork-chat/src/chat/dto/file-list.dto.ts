import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import { IsInt, IsOptional, IsString, Max, Min } from 'class-validator';

export class FileListQueryDto {
    @ApiPropertyOptional({ description: '커서: 이전 응답의 nextCursor 값' })
    @IsString()
    @IsOptional()
    before?: string;

    @ApiPropertyOptional({ description: '페이지 크기 (기본 20, 최대 100)', default: 20 })
    @IsInt()
    @Min(1)
    @Max(100)
    @IsOptional()
    @Type(() => Number)
    limit?: number;
}

export class FileListItemDto {
    @ApiProperty({ description: 'DELETE /files/{fileId}에 사용할 파일 식별자' }) fileId!: string;
    @ApiProperty() messageId!: string;
    @ApiProperty() fileName!: string;
    @ApiProperty() fileSize!: number;
    @ApiProperty() fileUrl!: string;
    @ApiProperty() mimeType!: string;
    @ApiProperty() uploaderId!: number;
    @ApiProperty() uploaderName!: string;
    @ApiProperty() uploadedAt!: string;
}

export class FileListResponseDto {
    @ApiProperty({ type: [FileListItemDto] })
    files!: FileListItemDto[];

    @ApiPropertyOptional({ nullable: true })
    nextCursor!: string | null;
}
