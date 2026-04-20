import { IsNumber, IsOptional, IsString } from 'class-validator';

export class MessagePayloadDto {
    @IsNumber() teamId!: number;
    @IsNumber() @IsOptional() projectId?: number | null;
    @IsNumber() channelId!: number;
    @IsNumber() authorId!: number;
    @IsString() content!: string;
}
