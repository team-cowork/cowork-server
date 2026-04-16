import { IsString } from 'class-validator';

export class MessagePayload {
    @IsString()
    channelId!: string;

    @IsString()
    content!: string;
}
