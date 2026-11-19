import {ConflictException, Injectable as injectable} from '@nestjs/common';
import {BlockRedis} from './block.redis.js';

// 무제한 차단 목록은 Redis 키 크기와 차단 검사 비용을 키우므로 사용자당 상한을 둔다.
export const MAX_BLOCKED_USERS = 1000;

/**
사용자 차단·해제 및 차단 상태 조회를 담당하는 서비스. DM 메시지 전송 시 차단 검사에 사용한다.
*/
@injectable()
export class BlockService {
	constructor(private readonly blockRedis: BlockRedis) {}

	/**
     사용자를 차단한다.

     @throws ConflictException 차단 목록이 상한에 도달한 경우
     */
	async blockUser(blockerId: number, targetId: number): Promise<void> {
		const isApplied = await this.blockRedis.block(blockerId, targetId, MAX_BLOCKED_USERS);
		if (!isApplied) {
			throw new ConflictException(`차단할 수 있는 사용자는 최대 ${MAX_BLOCKED_USERS}명입니다. 차단 목록을 정리한 뒤 다시 시도하세요.`);
		}
	}

	async unblockUser(blockerId: number, targetId: number): Promise<void> {
		await this.blockRedis.unblock(blockerId, targetId);
	}

	/**
     `receiverId` 가 `senderId` 를 차단했는지 확인한다.

     메시지 전송 전 수신자의 차단 여부를 검사할 때 사용한다.
     */
	async isBlocked(receiverId: number, senderId: number): Promise<boolean> {
		return this.blockRedis.isBlocked(receiverId, senderId);
	}

	async getBlockedUsers(userId: number): Promise<number[]> {
		return this.blockRedis.getBlockedIds(userId);
	}
}
