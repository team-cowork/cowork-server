import {IsBoolean, IsOptional} from 'class-validator';
import {ApiPropertyOptional} from '@nestjs/swagger';

export class StartMessageSearchRebuildDto {
    @ApiPropertyOptional({description: '중단된 재구축을 같은 index에서 이어서 진행'})
    @IsBoolean() @IsOptional() resume?: boolean;
}
