import {ApiProperty as apiProperty, ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';
import {
	IsNotEmpty as isNotEmpty,
	IsNumber as isNumber,
	IsOptional as isOptional,
	IsString as isString,
	MaxLength as maxLength,
} from 'class-validator';

export class CreateGithubIssueDto {
	@apiProperty({description: '프로젝트 ID', example: 100})
	@isNumber()
	projectId!: number;

	@apiProperty({description: '이슈 제목', example: '로그인 버그'})
	@isString()
	@isNotEmpty()
	@maxLength(256)
	title!: string;

	@apiPropertyOptional({description: '이슈 본문', example: '특정 브라우저에서 로그인이 안 됨'})
	@isString()
	@isOptional()
	body?: string;
}

export class CreateGithubIssueResponseDto {
	@apiProperty({description: '큐 등록 여부'})
	queued!: boolean;
}
