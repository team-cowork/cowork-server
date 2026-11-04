import {
	IsNotEmpty as isNotEmpty,
	IsNumber as isNumber,
	IsOptional as isOptional,
	IsString as isString,
} from 'class-validator';

export class PullRequestActionDto {
	@isString()
	@isNotEmpty()
	owner: string;

	@isString()
	@isNotEmpty()
	repo: string;

	@isNumber()
	prNumber: number;

	@isString()
	@isNotEmpty()
	requesterGithubUsername: string;

	@isNumber()
	@isOptional()
	channelId?: number;

	@isNumber()
	@isOptional()
	teamId?: number;
}
