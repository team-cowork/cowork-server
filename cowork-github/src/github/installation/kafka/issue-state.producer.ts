import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {Kafka, Producer} from 'kafkajs';
import {AppConfigService} from '../../../config/app-config.service.js';
import {IssueStateEvent} from './event/issue-state.js';
import {toRepoIdentity} from './event/repo-identity.js';
import {type SyncCompletedMarker, syncCompletedKey} from './event/sync-completed.js';

export const ISSUE_STATE_TOPIC = 'github.issue.state.v1';

@injectable()
export class IssueStateProducer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(IssueStateProducer.name);
	private producer!: Producer;

	constructor(private readonly config: AppConfigService) {}

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-github',
			...this.config.kafkaClientConfig,
		});
		this.producer = kafka.producer();
		await this.producer.connect();
		this.logger.log('Kafka issue-state producer connected');
	}

	async onModuleDestroy() {
		await this.producer.disconnect();
	}

	async send(event: IssueStateEvent): Promise<void> {
		const normalized = {...event, owner: toRepoIdentity(event.owner), repo: toRepoIdentity(event.repo)};
		await this.producer.send({
			topic: ISSUE_STATE_TOPIC,
			messages: [
				{
					key: `${normalized.owner}/${normalized.repo}#${normalized.number}`,
					value: JSON.stringify(normalized),
				},
			],
		});
	}

	async sendSyncCompleted(marker: SyncCompletedMarker): Promise<void> {
		await this.producer.send({
			topic: ISSUE_STATE_TOPIC,
			messages: [
				{
					key: syncCompletedKey(marker),
					value: JSON.stringify(marker),
				},
			],
		});
	}
}
