import {ApiProperty} from '@nestjs/swagger';
import {IsString, Matches, MaxLength} from 'class-validator';
import {WEBHOOK_CONTENT_MAX_LENGTH} from './webhook-policy';

export class ExecuteWebhookDto {
    @ApiProperty({maxLength: WEBHOOK_CONTENT_MAX_LENGTH})
    @IsString()
    @Matches(/\S/u)
    @MaxLength(WEBHOOK_CONTENT_MAX_LENGTH)
    content!: string;
}
