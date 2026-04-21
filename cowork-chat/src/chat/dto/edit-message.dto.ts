import { IsString, MaxLength } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

export class EditMessageDto {
    @ApiProperty({ description: '수정할 메시지 내용 (최대 25000자)', maxLength: 25000 })
    @IsString() @MaxLength(25000) content!: string;
}
