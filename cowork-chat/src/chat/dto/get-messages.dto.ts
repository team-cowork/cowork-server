import { IsOptional, IsMongoId } from 'class-validator';

export class GetMessagesDto {
    @IsMongoId() @IsOptional() before?: string;
}
