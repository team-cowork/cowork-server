import { IsOptional, IsString } from 'class-validator';

export class GetMessagesDto {
    @IsString() @IsOptional() before?: string;
}
