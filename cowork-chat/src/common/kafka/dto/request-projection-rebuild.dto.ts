import { IsString, MaxLength, MinLength } from 'class-validator';

export class RequestProjectionRebuildDto {
    @IsString()
    @MinLength(3)
    @MaxLength(500)
    reason!: string;
}
