import {Inject as inject, Injectable as injectable} from '@nestjs/common';
import type {Redis} from 'ioredis';
import {REDIS_CLIENT} from '../../constants.js';

// 명령 보존 7일과 활성 Kafka 세그먼트의 최대 7일 동안 생성 이력을 유지한다.
const HISTORY_TTL_SECONDS = 14 * 24 * 60 * 60;
const historyKey = (operationId: string): string => `github:project-draft:${operationId}`;

export type ProjectDraftRecord = {
	fingerprint: string;
	itemId?: string;
	error?: {message: string; statusCode: number};
};

@injectable()
export class ProjectDraftHistory {
	constructor(@inject(REDIS_CLIENT) private readonly redis: Redis) {}

	async claim(operationId: string, fingerprint: string): Promise<{record: ProjectDraftRecord; claimed: boolean}> {
		const record: ProjectDraftRecord = {fingerprint};
		if (await this.redis.set(historyKey(operationId), JSON.stringify(record), 'EX', HISTORY_TTL_SECONDS, 'NX') === 'OK') {
			return {record, claimed: true};
		}

		const existing = await this.redis.get(historyKey(operationId));
		if (existing === null) {
			throw new Error('Project draft history expired during claim');
		}

		return {record: JSON.parse(existing) as ProjectDraftRecord, claimed: false};
	}

	async save(operationId: string, record: ProjectDraftRecord): Promise<void> {
		await this.redis.set(historyKey(operationId), JSON.stringify(record), 'EX', HISTORY_TTL_SECONDS);
	}
}
