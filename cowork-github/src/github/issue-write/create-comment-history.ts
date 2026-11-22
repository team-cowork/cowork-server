import {randomUUID} from 'node:crypto';
import {Inject as inject, Injectable as injectable} from '@nestjs/common';
import type {Redis} from 'ioredis';
import {REDIS_CLIENT} from '../constants.js';
import {CREATE_COMMENT_HISTORY_TTL_SECONDS, githubCacheKeys, SET_GITHUB_CACHE_VALUE_IF_MATCHES} from '../github.cache.js';
import {type IssueWriteResultEvent} from './event/issue-write-result.event.js';

// RECEIVED: GitHub 호출 전 접수. isRetryable이 없으면 재전달에서 외부 결과를 확인한다.
// COMPLETED: GitHub 결과(성공 또는 4xx 실패)를 확정해 결과 이벤트를 저장했다.
// DELIVERED: 결과 이벤트 발행까지 끝났다.
export type CreateCommentRecord = {fingerprint: string; receivedAt: string; claimId?: string} & (
	| {state: 'RECEIVED'; isRetryable?: true}
	| {state: 'COMPLETED' | 'DELIVERED'; event: IssueWriteResultEvent}
);

@injectable()
export class CreateCommentHistory {
	constructor(@inject(REDIS_CLIENT) private readonly redis: Redis) {}

	// 처음 받은 명령이면 RECEIVED로 선점하고, 이미 이력이 있으면 그 이력을 돌려준다.
	async claim(operationId: string, fingerprint: string): Promise<{record: CreateCommentRecord; claimed: boolean}> {
		const record: CreateCommentRecord = {
			state: 'RECEIVED', fingerprint, receivedAt: new Date().toISOString(), claimId: randomUUID(),
		};
		const key = githubCacheKeys.createCommentHistory(operationId);
		const pending = JSON.stringify(record);
		if (await this.redis.set(key, pending, 'EX', CREATE_COMMENT_HISTORY_TTL_SECONDS, 'NX') === 'OK') {
			return {record, claimed: true};
		}

		const existing = await this.redis.get(key);
		if (existing === null) {
			throw new Error(`Create-comment history expired during claim: ${operationId}`);
		}

		const stored = JSON.parse(existing) as CreateCommentRecord;
		if (stored.state === 'RECEIVED' && stored.isRetryable === true && stored.fingerprint === fingerprint) {
			const updated = await this.redis.eval(
				SET_GITHUB_CACHE_VALUE_IF_MATCHES, 1, key, existing, pending, CREATE_COMMENT_HISTORY_TTL_SECONDS,
			);
			if (updated !== 1) {
				throw new Error('Create-comment history changed during claim');
			}

			return {record, claimed: true};
		}

		return {record: stored, claimed: false};
	}

	async markRetryable(operationId: string, record: Extract<CreateCommentRecord, {state: 'RECEIVED'}>): Promise<void> {
		await this.redis.eval(
			SET_GITHUB_CACHE_VALUE_IF_MATCHES,
			1,
			githubCacheKeys.createCommentHistory(operationId),
			JSON.stringify(record),
			JSON.stringify({...record, isRetryable: true}),
			CREATE_COMMENT_HISTORY_TTL_SECONDS,
		);
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
