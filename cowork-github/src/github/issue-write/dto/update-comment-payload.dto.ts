import {IsNotEmpty as isNotEmpty, IsNumber as isNumber, IsString as isString} from 'class-validator';

export class UpdateCommentPayloadDto {
	@isNumber()
	commentId: number;

	@isString()
	@isNotEmpty()
	body: string;
}
