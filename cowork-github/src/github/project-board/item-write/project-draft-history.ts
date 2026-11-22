import {randomUUID} from 'node:crypto';
import {Inject as inject, Injectable as injectable} from '@nestjs/common';
import type {Redis} from 'ioredis';
import {REDIS_CLIENT} from '../../constants.js';
import {SET_GITHUB_CACHE_VALUE_IF_MATCHES} from '../../github.cache.js';

// 명령 보존 7일과 활성 Kafka 세그먼트의 최대 7일 동안 생성 이력을 유지한다.
const HISTORY_TTL_SECONDS = 14 * 24 * 60 * 60;
const historyKey = (operationId: string): string => `github:project-draft:${operationId}`;

export type ProjectDraftRecord = {
	fingerprint: string;
	claimId?: string;
	isRetryable?: true;
	itemId?: string;
	error?: {message: string; statusCode: number};
};

@injectable()
export class ProjectDraftHistory {
	constructor(@inject(REDIS_CLIENT) private readonly redis: Redis) {}

	async claim(operationId: string, fingerprint: string): Promise<{record: ProjectDraftRecord; claimed: boolean}> {
		const record: ProjectDraftRecord = {fingerprint, claimId: randomUUID()};
		const key = historyKey(operationId);
		const pending = JSON.stringify(record);
		if (await this.redis.set(key, pending, 'EX', HISTORY_TTL_SECONDS, 'NX') === 'OK') {
			return {record, claimed: true};
		}

		const existing = await this.redis.get(key);
		if (existing === null) {
			throw new Error('Project draft history expired during claim');
		}

		const stored = JSON.parse(existing) as ProjectDraftRecord;
		if (stored.isRetryable === true && stored.fingerprint === fingerprint) {
			const updated = await this.redis.eval(SET_GITHUB_CACHE_VALUE_IF_MATCHES, 1, key, existing, pending, HISTORY_TTL_SECONDS);
			if (updated !== 1) {
				throw new Error('Project draft history changed during claim');
			}

			return {record, claimed: true};
		}

		return {record: stored, claimed: false};
	}

	async markRetryable(operationId: string, record: ProjectDraftRecord): Promise<void> {
		await this.redis.eval(
			SET_GITHUB_CACHE_VALUE_IF_MATCHES,
			1,
			historyKey(operationId),
			JSON.stringify(record),
			JSON.stringify({...record, isRetryable: true}),
			HISTORY_TTL_SECONDS,
		);
	}

	async save(operationId: string, record: ProjectDraftRecord): Promise<void> {
		await this.redis.set(historyKey(operationId), JSON.stringify(record), 'EX', HISTORY_TTL_SECONDS);
	}
}
