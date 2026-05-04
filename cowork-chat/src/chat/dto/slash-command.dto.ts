import { ApiProperty } from '@nestjs/swagger';
import { Type } from 'class-transformer';
import { IsEnum, ValidateNested } from 'class-validator';
import { CreateGithubIssueDto } from './create-github-issue.dto';

export enum SlashCommand {
    GITHUB_ISSUE_CREATE = 'github.issue.create',
}

export class GithubIssueSlashCommandPayloadDto extends CreateGithubIssueDto {}

export class SlashCommandDto {
    @ApiProperty({
        description: '실행할 슬래시 커맨드',
        enum: SlashCommand,
        example: SlashCommand.GITHUB_ISSUE_CREATE,
    })
    @IsEnum(SlashCommand)
    command!: SlashCommand;

    @ApiProperty({
        description: '커맨드별 구조화 payload',
        type: GithubIssueSlashCommandPayloadDto,
    })
    @ValidateNested()
    @Type(() => GithubIssueSlashCommandPayloadDto)
    payload!: GithubIssueSlashCommandPayloadDto;
}
