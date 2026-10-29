import {IsString as isString, MaxLength as maxLength, MinLength as minLength} from 'class-validator';
import {ApiProperty as apiProperty} from '@nestjs/swagger';

export class EditMessageDto {
	@apiProperty({description: '수정할 메시지 내용 (최대 25000자)', minLength: 1, maxLength: 25_000})
	@isString() @minLength(1) @maxLength(25_000) content!: string;
}
