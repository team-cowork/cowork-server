import {IsBoolean as isBoolean, IsOptional as isOptional} from 'class-validator';
import {ApiPropertyOptional as apiPropertyOptional} from '@nestjs/swagger';

export class StartMessageSearchRebuildDto {
	@apiPropertyOptional({description: 'true이면 중단된 재구축을 같은 색인에서 이어서 진행합니다.'})
	@isBoolean() @isOptional() resume?: boolean;
}
