import { IsString, IsOptional, IsInt, IsEnum, IsBoolean, Min, Max, MinLength } from 'class-validator';
import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { Transform, Type } from 'class-transformer';

export class SearchMessagesDto {
    @ApiProperty({ description: '검색 키워드 (최소 1자)', minLength: 1 })
    @IsString() @MinLength(1) q!: string;

    @ApiPropertyOptional({ description: '특정 채널로 범위 축소' })
    @IsInt() @IsOptional() @Type(() => Number) channelId?: number;

    @ApiPropertyOptional({ description: '특정 작성자 필터' })
    @IsInt() @IsOptional() @Type(() => Number) authorId?: number;

    @ApiPropertyOptional({ enum: ['TEXT', 'FILE', 'SYSTEM'], description: '메시지 타입 필터' })
    @IsEnum(['TEXT', 'FILE', 'SYSTEM']) @IsOptional() type?: string;

    @ApiPropertyOptional({ description: '첨부파일 포함 메시지만 조회' })
    @IsBoolean() @IsOptional() @Transform(({ value }) => value === 'true' || value === true) hasFile?: boolean;

    @ApiPropertyOptional({ description: '커서: 이전 응답의 nextCursor 값' })
    @IsString() @IsOptional() before?: string;

    @ApiPropertyOptional({ description: '페이지 크기 (기본 50, 최대 100)', default: 50 })
    @IsInt() @Min(1) @Max(100) @IsOptional() @Type(() => Number) limit?: number;
}
