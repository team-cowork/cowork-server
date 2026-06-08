import { IsString, MaxLength } from 'class-validator';
import { ApiProperty } from '@nestjs/swagger';

export class EditMessageDto {
    @ApiProperty()
    @IsString()
    @MaxLength(25000)
    content!: string;
}
