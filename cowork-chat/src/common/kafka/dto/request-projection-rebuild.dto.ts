import {IsString as isString, MaxLength as maxLength, MinLength as minLength} from 'class-validator';
import {ApiProperty as apiProperty} from '@nestjs/swagger';

export class RequestProjectionRebuildDto {
	@apiProperty({description: '재구축 사유', minLength: 3, maxLength: 500})
	@isString()
	@minLength(3)
	@maxLength(500)
	reason!: string;
}
