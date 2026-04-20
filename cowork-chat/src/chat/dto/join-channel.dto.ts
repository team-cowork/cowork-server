import { IsNumber } from 'class-validator';

export class JoinChannelDto {
    @IsNumber() channelId!: number;
}
