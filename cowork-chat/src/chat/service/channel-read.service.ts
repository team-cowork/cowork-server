import {Injectable as injectable} from '@nestjs/common';
import {Types} from 'mongoose';
import {ChannelReadStateRepository, MessageRepository} from '../repository/index.js';
import {type Nullable} from '../../common/type/nullable.js';
import {ChannelMessageReadAccessService} from './channel-message-read-access.service.js';
import {UnreadCounterService} from './unread-counter.service.js';

/**
 읽음 위치를 전진시키고 안읽음 수 캐시를 같은 기준으로 맞춘다.

 채널 읽음 요청과 작성자의 메시지 전송처럼 읽음 위치를 바꾸는 모든 경로가 이 서비스를 거쳐야,
 DB의 최종 읽음 위치와 캐시·실시간 표시값이 어긋나지 않는다.
 */
@injectable()
export class ChannelReadService {
	constructor(
		private readonly readStateRepository: ChannelReadStateRepository,
		private readonly messageRepository: MessageRepository,
		private readonly unreadCounterService: UnreadCounterService,
		private readonly channelMessageReadAccess: ChannelMessageReadAccessService,
	) {}

	/**
     읽음 위치를 `messageId`까지 전진시키고, DB가 채택한 위치 기준의 안읽음 수를 캐시에 기록한다.

     `shouldExcludeSystem`이 true면 SYSTEM 메시지를 세지 않는다. 음성 채널 로그를 볼 수 없는 사용자에게 쓴다.

     @returns 전송할 안읽음 수. 더 최신 읽음 위치로 계산한 값이 이미 기록되어 이 값이 오래되었으면 `null`
     */
	async advance(channelId: number, userId: number, messageId: Types.ObjectId, shouldExcludeSystem = false): Promise<Nullable<number>> {
		const joinedAt = await this.channelMessageReadAccess.requireChannelJoinedAt(channelId, userId);
		// 요청값이 아니라 DB가 채택한 읽음 위치로 센다 — 다른 기기의 늦은 요청이 이미 읽은 메시지를 다시 세지 않게 한다.
		const readCursor = await this.readStateRepository.updateLastRead(channelId, userId, messageId);
		const unreadCount = await this.messageRepository.countUnread(channelId, readCursor, shouldExcludeSystem, joinedAt);
		const applied = await this.unreadCounterService.set(channelId, userId, {count: unreadCount, joinedAt, readCursor: readCursor.toHexString()});
		// 거부되었다면 더 최신 읽음 위치로 계산한 값이 이미 기록·전송되었다. Redis 오류로 알 수 없으면 계산값을 보낸다.
		return applied === false ? null : unreadCount;
	}
}
