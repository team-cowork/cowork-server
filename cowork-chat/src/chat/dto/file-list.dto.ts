import {ApiProperty as apiProperty, ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';
import {Type as transformType} from 'class-transformer';
import {
	IsInt as isInt,
	IsOptional as isOptional,
	IsString as isString,
	Max as max,
	Min as min,
} from 'class-validator';
import {type Nullable} from '../../common/type/nullable.js';

export class FileListQueryDto {
	@apiPropertyOptional({description: '커서: 이전 응답의 nextCursor 값'})
	@isString()
	@isOptional()
	before?: string;

	@apiPropertyOptional({description: '페이지 크기 (기본 20, 최대 100)', default: 20})
	@isInt()
	@min(1)
	@max(100)
	@isOptional()
	@transformType(() => Number)
	limit?: number;
}

export class FileListItemDto {
	@apiProperty({description: 'DELETE /files/{fileId}에 사용할 파일 식별자'}) fileId!: string;
	@apiProperty() messageId!: string;
	@apiProperty() fileName!: string;
	@apiProperty() fileSize!: number;
	@apiProperty() attachmentIndex!: number;
	@apiPropertyOptional() objectKey?: string;
	@apiProperty() mimeType!: string;
	@apiProperty() uploaderId!: number;
	@apiProperty() uploaderName!: string;
	@apiProperty() uploadedAt!: string;
}

export class FileListResponseDto {
	@apiProperty({type: [FileListItemDto]})
	files!: FileListItemDto[];

	@apiPropertyOptional({nullable: true})
	nextCursor!: Nullable<string>;
}
