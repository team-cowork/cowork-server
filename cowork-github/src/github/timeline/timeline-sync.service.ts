import {randomUUID} from 'node:crypto';
import {Injectable as injectable} from '@nestjs/common';
import {AppConfigService} from '../../config/app-config.service.js';
import {KafkaProducerService} from '../../kafka/kafka-producer.service.js';
import {GithubClientError} from '../github.errors.js';
import {toTimelineActivity} from './timeline-activity.js';
import {TimelineApiClient, type TimelineParent} from './timeline-api.client.js';

const TOPIC = 'github.timeline.state.v1';

@injectable()
export class TimelineSyncService {
	constructor(
		private readonly client: TimelineApiClient,
		private readonly kafka: KafkaProducerService,
		private readonly config: AppConfigService,
	) {}

	async sync(owner: string, repo: string, number: number, renew: () => Promise<void>): Promise<void> {
		const subject = `${owner.toLowerCase()}/${repo.toLowerCase()}#${number}`;
		const snapshotId = randomUUID();
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
				// Kafka 레코드 크기를 활동 한 건으로 제한한다. 완료 레코드는 삭제된 활동까지 포함해 이전 스냅샷을 대체한다.
				const sent = await Promise.allSettled(entries.map(async item =>
					this.kafka.send(TOPIC, `${subject}:${item.id}`, {subject, snapshotId, item})));
				const failure = sent.find(result => result.status === 'rejected');
				if (failure !== undefined) {
					throw failure.reason instanceof Error ? failure.reason : new Error('Failed to publish GitHub timeline activity');
				}
			}
		}

		await renew();
		await this.kafka.send(TOPIC, `${subject}:complete`, {
			subject, snapshotId, parent, count: seen.size, completedAt: new Date().toISOString(),
		});
	}
}
