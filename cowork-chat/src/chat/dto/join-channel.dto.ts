import {IsInt as isInt, IsPositive as isPositive, Max as max} from 'class-validator';

/**
 WebSocket 채널 입장 요청 DTO.
 클라이언트가 특정 채널의 소켓 룸에 join할 때 전달한다.
 */
export class JoinChannelDto {
	@isInt() @isPositive() @max(Number.MAX_SAFE_INTEGER) channelId!: number;
}
