import {IsArray as isArray, IsNumber as isNumber, IsString as isString} from 'class-validator';

export class ReplaceLabelsPayloadDto {
	@isNumber()
	issueNumber: number;

	@isArray()
	@isString({each: true})
	labels: string[];
}
