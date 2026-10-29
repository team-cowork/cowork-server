import {ApiProperty as apiProperty} from '@nestjs/swagger';
import {Type as transformType} from 'class-transformer';
import {IsEnum as isEnum, ValidateNested as validateNested} from 'class-validator';
import {CreateGithubIssueDto} from './create-github-issue.dto.js';

export enum SlashCommand {
	GITHUB_ISSUE_CREATE = 'github.issue.create',
}

export class GithubIssueSlashCommandPayloadDto extends CreateGithubIssueDto {}

export class SlashCommandDto {
	@apiProperty({
		description: '실행할 슬래시 커맨드',
		enum: SlashCommand,
		example: SlashCommand.GITHUB_ISSUE_CREATE,
	})
	@isEnum(SlashCommand)
	command!: SlashCommand;

	@apiProperty({
		description: '커맨드별 구조화 payload',
		type: GithubIssueSlashCommandPayloadDto,
	})
	@validateNested()
	@transformType(() => GithubIssueSlashCommandPayloadDto)
	payload!: GithubIssueSlashCommandPayloadDto;
}

export class SlashCommandResponseDto {
	@apiProperty({description: '큐 등록 여부'})
	queued!: boolean;
}
