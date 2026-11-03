import {IsNotEmpty as isNotEmpty, IsString as isString} from 'class-validator';

export class PullRequestActionRequestDto {
	@isString()
	@isNotEmpty()
	requesterGithubUsername: string;
}
