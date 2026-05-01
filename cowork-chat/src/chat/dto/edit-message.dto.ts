import { IsString, MaxLength, MinLength } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

export class EditMessageDto {
    @ApiProperty({ description: '수정할 메시지 내용 (최대 25000자)', minLength: 1, maxLength: 25000 })
    @IsString() @MinLength(1) @MaxLength(25000) content!: string;
}
