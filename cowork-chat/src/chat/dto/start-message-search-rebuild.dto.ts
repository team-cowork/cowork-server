import {IsBoolean as isBoolean, IsOptional as isOptional} from 'class-validator';
import {ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';

export class StartMessageSearchRebuildDto {
	@apiPropertyOptional({description: '중단된 재구축을 같은 index에서 이어서 진행'})
	@isBoolean() @isOptional() resume?: boolean;
}
