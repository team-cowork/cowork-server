import { IsString, MaxLength } from 'class-validator';

export class EditMessageDto {
    @IsString() @MaxLength(25000) content!: string;
}
