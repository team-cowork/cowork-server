import {
	IsString as isString,
	IsOptional as isOptional,
	IsInt as isInt,
	IsEnum as isEnum,
	IsBoolean as isBoolean,
	Min as min,
	Max as max,
	MinLength as minLength,
} from 'class-validator';
import {ApiProperty as apiProperty, ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';
import {Transform as transform, Type as transformType} from 'class-transformer';

export class SearchTeamMessagesDto {
	@apiProperty({description: '검색 키워드 (최소 1자)', minLength: 1})
	@isString() @minLength(1) q!: string;

	@apiProperty({description: '팀 ID'})
	@isInt() @min(1) @max(Number.MAX_SAFE_INTEGER) @transformType(() => Number) teamId!: number;

	@apiPropertyOptional({description: '특정 채널로 범위 축소'})
	@isInt() @min(1) @max(Number.MAX_SAFE_INTEGER) @isOptional() @transformType(() => Number) channelId?: number;

	@apiPropertyOptional({description: '특정 작성자 필터'})
	@isInt() @min(1) @max(Number.MAX_SAFE_INTEGER) @isOptional() @transformType(() => Number) authorId?: number;

	@apiPropertyOptional({enum: ['TEXT', 'FILE', 'SYSTEM'], description: '메시지 타입 필터'})
	@isEnum(['TEXT', 'FILE', 'SYSTEM']) @isOptional() type?: string;

	@apiPropertyOptional({description: '첨부파일 포함 메시지만 조회'})
	@isBoolean() @isOptional() @transform(({value}) => value === 'true' || value === true) hasFile?: boolean;

	@apiPropertyOptional({description: '커서: 이전 응답의 nextCursor 값'})
	@isString() @isOptional() before?: string;

	@apiPropertyOptional({description: '페이지 크기 (기본 50, 최대 100)', default: 50})
	@isInt() @min(1) @max(100) @isOptional() @transformType(() => Number) limit?: number;
}
