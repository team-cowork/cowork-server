import {IsNumber as isNumber} from 'class-validator';

export class DeleteCommentPayloadDto {
	@isNumber()
	commentId: number;
}
