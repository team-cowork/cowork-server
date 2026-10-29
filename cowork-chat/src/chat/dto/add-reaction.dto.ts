import {ApiProperty as apiProperty} from '@nestjs/swagger';
import {IsString as isString, Matches as matches} from 'class-validator';
import {EMOJI_REGEX} from '../util/index.js';

export class AddReactionDto {
	@apiProperty({example: '👍'})
	@isString()
	@matches(EMOJI_REGEX, {
		message: 'Invalid emoji format',
	})
	emoji!: string;
}
