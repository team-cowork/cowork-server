import {IsString as isString, MaxLength as maxLength, MinLength as minLength} from 'class-validator';

export class RequestProjectionRebuildDto {
	@isString()
	@minLength(3)
	@maxLength(500)
	reason!: string;
}
