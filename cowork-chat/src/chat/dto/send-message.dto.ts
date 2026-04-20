import { IsNumber, IsOptional, IsString, MaxLength, IsEnum, IsArray, IsMongoId } from 'class-validator';

export class AttachmentDto {
    @IsString() name!: string;
    @IsString() url!: string;
    @IsNumber() size!: number;
    @IsString() mimeType!: string;
}

export class SendMessageDto {
    @IsNumber() teamId!: number;
    @IsNumber() @IsOptional() projectId?: number | null;
    @IsNumber() channelId!: number;
    @IsString() @MaxLength(25000) content!: string;
    @IsEnum(['TEXT', 'FILE']) @IsOptional() type?: string;
    @IsArray() @IsOptional() attachments?: AttachmentDto[];
    @IsMongoId() @IsOptional() parentMessageId?: string;
    @IsString() @IsOptional() clientMessageId?: string;
}
