import {Inject, Injectable} from '@nestjs/common';
import type Redis from 'ioredis';
import {REDIS_CLIENT} from '../constants';
import {CREATE_COMMENT_HISTORY_TTL_SECONDS, githubCacheKeys} from '../github.cache';
import {type IssueWriteResultEvent} from './event/issue-write-result.event';

// RECEIVED: GitHub 호출 전 접수. 재전달에서 이 상태를 보면 외부 결과를 모른다.
// COMPLETED: GitHub 결과(성공 또는 4xx 실패)를 확정해 결과 이벤트를 저장했다.
// DELIVERED: 결과 이벤트 발행까지 끝났다.
export type CreateCommentRecord = {fingerprint: string; receivedAt: string} & (
    | {state: 'RECEIVED'}
    | {state: 'COMPLETED' | 'DELIVERED'; event: IssueWriteResultEvent}
);

@Injectable()
export class CreateCommentHistory {
    constructor(@Inject(REDIS_CLIENT) private readonly redis: Redis) {}

    // 처음 받은 명령이면 RECEIVED로 선점하고, 이미 이력이 있으면 그 이력을 돌려준다.
    async claim(operationId: string, fingerprint: string): Promise<{record: CreateCommentRecord; claimed: boolean}> {
        const record: CreateCommentRecord = {state: 'RECEIVED', fingerprint, receivedAt: new Date().toISOString()};
        const key = githubCacheKeys.createCommentHistory(operationId);
        if (await this.redis.set(key, JSON.stringify(record), 'EX', CREATE_COMMENT_HISTORY_TTL_SECONDS, 'NX') === 'OK') {
            return {record, claimed: true};
        }

        const existing = await this.redis.get(key);
        if (existing === null) {
            throw new Error(`Create-comment history expired during claim: ${operationId}`);
        }

        return {record: JSON.parse(existing) as CreateCommentRecord, claimed: false};
    }

    async save(operationId: string, record: CreateCommentRecord): Promise<void> {
        await this.redis.set(
            githubCacheKeys.createCommentHistory(operationId),
            JSON.stringify(record),
            'EX',
            CREATE_COMMENT_HISTORY_TTL_SECONDS,
        );
    }
}
