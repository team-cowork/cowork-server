import { Injectable } from '@nestjs/common';
import { BlockRedis } from './block.redis';

/** 사용자 차단·해제 및 차단 상태 조회를 담당하는 서비스. DM 메시지 전송 시 차단 검사에 사용한다. */
@Injectable()
export class BlockService {
    constructor(private readonly blockRedis: BlockRedis) {}

    /**
     * 사용자를 차단한다.
     *
     * @param blockerId - 차단하는 사용자 ID
     * @param targetId - 차단 대상 사용자 ID
     */
    async blockUser(blockerId: number, targetId: number): Promise<void> {
        await this.blockRedis.block(blockerId, targetId);
    }

    /**
     * 사용자 차단을 해제한다.
     *
     * @param blockerId - 차단 해제하는 사용자 ID
     * @param targetId - 차단 해제 대상 사용자 ID
     */
    async unblockUser(blockerId: number, targetId: number): Promise<void> {
        await this.blockRedis.unblock(blockerId, targetId);
    }

    /**
     * `receiverId` 가 `senderId` 를 차단했는지 확인한다.
     *
     * 메시지 전송 전 수신자의 차단 여부를 검사할 때 사용한다.
     *
     * @param receiverId - 차단 여부를 확인할 수신자 ID
     * @param senderId - 발신자 ID
     * @returns 차단된 경우 `true`
     */
    isBlocked(receiverId: number, senderId: number): Promise<boolean> {
        return this.blockRedis.isBlocked(receiverId, senderId);
    }

    /**
     * 사용자가 차단한 사용자 ID 목록을 반환한다.
     *
     * @param userId - 조회할 사용자 ID
     * @returns 차단한 사용자 ID 목록
     */
    async getBlockedUsers(userId: number): Promise<number[]> {
        return this.blockRedis.getBlockedIds(userId);
    }
}
