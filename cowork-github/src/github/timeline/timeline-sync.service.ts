import {randomUUID} from 'node:crypto';
import {Inject as inject, Injectable as injectable, type OnModuleDestroy} from '@nestjs/common';
import type {Redis} from 'ioredis';
import {AppConfigService} from '../../config/app-config.service.js';
import {KafkaProducerService} from '../../kafka/kafka-producer.service.js';
import {forEachSequential} from '../common/sequential.js';
import {REDIS_CLIENT} from '../constants.js';
import {githubCacheKeys} from '../github.cache.js';
import {GithubClientError} from '../github.errors.js';
import {toTimelineActivity} from './timeline-activity.js';
import {TimelineApiClient, type TimelineParent} from './timeline-api.client.js';

const TOPIC = 'github.timeline.state.v1';

@injectable()
export class TimelineSyncService implements OnModuleDestroy {
	private readonly manifestRedis: Redis;

	constructor(
		private readonly client: TimelineApiClient,
		private readonly kafka: KafkaProducerService,
		private readonly config: AppConfigService,
		@inject(REDIS_CLIENT) redis: Redis,
	) {
		// 식별자의 디스크 기록을 기다리는 동안 인증과 백필 락의 Redis 요청을 막지 않는다.
		this.manifestRedis = redis.duplicate({lazyConnect: true});
	}

	private async rememberIds(manifest: string, ids: string[]): Promise<void> {
		const writes = await this.manifestRedis.pipeline()
			.sadd(manifest, ...ids)
			.call('WAITAOF', 1, 0, 5000)
			.exec();
		const persisted = writes?.[1]?.[1];
		if (!writes || writes.some(([error]) => error !== null) || !Array.isArray(persisted) || persisted[0] !== 1) {
			throw new Error('Failed to persist GitHub timeline activity identifiers');
		}
	}

	onModuleDestroy(): void {
		this.manifestRedis.disconnect();
	}

	async sync(owner: string, repo: string, number: number, renew: () => Promise<void>): Promise<void> {
		const subject = `${owner.toLowerCase()}/${repo.toLowerCase()}#${number}`;
		const snapshotId = randomUUID();
		const manifest = githubCacheKeys.timelineActivityIds(subject);
		const seen = new Set<string>();
		let parent: TimelineParent | 'MISSING';
		try {
			parent = await this.client.parent(owner, repo, number);
		} catch (error) {
			if (!(error instanceof GithubClientError) || ![301, 404, 410].includes(error.statusCode)) {
				throw error;
			}

			parent = 'MISSING';
		}

		await renew();
		await this.kafka.send(TOPIC, `${subject}:start`, {subject, snapshotId, start: true});
		if (parent !== 'MISSING') {
			for await (const events of this.client.pages(owner, repo, number, parent)) {
				await renew();
				const entries = events.flatMap(event => {
					const item = toTimelineActivity(event, this.config.githubAppId);
					if (item === undefined || seen.has(item.id)) {
						return [];
					}

					seen.add(item.id);
					return [item];
				});
				if (entries.length === 0) {
					continue;
				}

				// 발행 중 실패한 페이지도 다음 동기화에서 삭제할 수 있게 식별자부터 기록한다.
				await this.rememberIds(manifest, entries.map(item => item.id));
				// Kafka 레코드 크기를 활동 한 건으로 제한한다. 완료 레코드는 삭제된 활동까지 포함해 이전 스냅샷을 대체한다.
				const sent = await Promise.allSettled(entries.map(async item =>
					this.kafka.send(TOPIC, `${subject}:${item.id}`, {subject, snapshotId, item})));
				const failure = sent.find(result => result.status === 'rejected');
				if (failure !== undefined) {
					throw failure.reason instanceof Error ? failure.reason : new Error('Failed to publish GitHub timeline activity');
				}
			}
		}

		const publishedIds = await this.manifestRedis.smembers(manifest);
		const removed = publishedIds.filter(id => !seen.has(id));
		await forEachSequential(removed, async id => {
			await renew();
			await this.kafka.send(TOPIC, `${subject}:${id}`, {subject, snapshotId, deletedActivityId: id});
			// 브로커가 삭제 레코드를 확인한 뒤에만 지운다. 도중에 중단되면 다음 동기화에서 재전송한다.
			await renew();
			await this.manifestRedis.srem(manifest, id);
		});

		await renew();
		await this.kafka.send(TOPIC, `${subject}:complete`, {
			subject, snapshotId, parent, count: seen.size, completedAt: new Date().toISOString(),
		});
	}
}
