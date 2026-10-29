import {ApiProperty as apiProperty} from '@nestjs/swagger';
import {IsString as isString, Matches as matches, MaxLength as maxLength} from 'class-validator';
import {WEBHOOK_CONTENT_MAX_LENGTH} from './webhook-policy.js';

export class ExecuteWebhookDto {
	@apiProperty({maxLength: WEBHOOK_CONTENT_MAX_LENGTH})
	@isString()
	@matches(/\S/v)
	@maxLength(WEBHOOK_CONTENT_MAX_LENGTH)
	content!: string;
}
