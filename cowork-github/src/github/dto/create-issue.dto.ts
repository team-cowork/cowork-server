import {
	IsArray as isArray,
	IsNotEmpty as isNotEmpty,
	IsNumber as isNumber,
	IsOptional as isOptional,
	IsString as isString,
} from 'class-validator';

export class CreateIssueDto {
	@isString()
	@isNotEmpty()
	owner: string;

	@isString()
	@isNotEmpty()
	repo: string;

	@isString()
	@isNotEmpty()
	title: string;

	@isString()
	@isOptional()
	body?: string;

	@isArray()
	@isString({each: true})
	@isOptional()
	labels?: string[];

	@isArray()
	@isString({each: true})
	@isOptional()
	assignees?: string[];

	@isNumber()
	@isOptional()
	channelId?: number;

	@isNumber()
	@isOptional()
	teamId?: number;
}
