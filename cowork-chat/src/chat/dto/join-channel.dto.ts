import { IsInt, IsPositive, Max } from 'class-validator';

/**
 * WebSocket 채널 입장 요청 DTO.
 * 클라이언트가 특정 채널의 소켓 룸에 join할 때 전달한다.
 */
export class JoinChannelDto {
    @IsInt() @IsPositive() @Max(Number.MAX_SAFE_INTEGER) channelId!: number;
}
