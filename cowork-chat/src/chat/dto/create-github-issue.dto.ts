import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger';
import { IsNotEmpty, IsNumber, IsOptional, IsString, MaxLength } from 'class-validator';

export class CreateGithubIssueDto {
    @ApiProperty({ description: '팀 ID', example: 1 })
    @IsNumber()
    teamId!: number;

    @ApiProperty({ description: '프로젝트 ID', example: 100 })
    @IsNumber()
    projectId!: number;

    @ApiProperty({ description: '이슈 제목', example: '로그인 버그' })
    @IsString()
    @IsNotEmpty()
    @MaxLength(256)
    title!: string;

    @ApiPropertyOptional({ description: '이슈 본문', example: '특정 브라우저에서 로그인이 안 됨' })
    @IsString()
    @IsOptional()
    body?: string;
}

export class CreateGithubIssueResponseDto {
    @ApiProperty({ description: '큐 등록 여부' })
    queued!: boolean;
}
