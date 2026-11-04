import {IsNotEmpty as isNotEmpty, IsNumber as isNumber, IsString as isString} from 'class-validator';

export class CreateCommentPayloadDto {
	@isNumber()
	issueNumber: number;

	@isString()
	@isNotEmpty()
	body: string;

	@isString()
	@isNotEmpty()
	requesterGithubUsername: string;
}
