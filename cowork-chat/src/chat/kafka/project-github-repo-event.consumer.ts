import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {DicoshotService} from 'dicoshot-nest';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {buildErrorFields} from '../../common/util/discord-alert.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {applyProjectionMessage, ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {ProjectGithubRepoProjectionRepository} from '../repository/index.js';
import {connectWithRetry} from '../../common/kafka/connect-with-retry.js';
import {type Nullable} from '../../common/type/nullable.js';

type ProjectGithubRepoEvent = {
	schemaVersion: 1;
	eventType: 'UPSERT' | 'DELETE';
	repoId: number;
	projectId: number;
	teamId: number;
	githubRepoUrl: Nullable<string>;
	owner: Nullable<string>;
	repo: Nullable<string>;
	webhookChannelId: Nullable<number>;
	occurredAt: string;
	snapshot?: boolean;
};

@injectable()
export class ProjectGithubRepoEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(ProjectGithubRepoEventConsumer.name);
	private consumer!: Consumer;

	constructor(
		private readonly configService: ConfigService,
		private readonly dicoshot: DicoshotService,
		private readonly repository: ProjectGithubRepoProjectionRepository,
		private readonly projectionReadiness: ProjectionReadinessService,
	) {}

	private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
		if (!this.isProjectGithubRepoEvent(payload)) {
			throw new ProjectionContractError('invalid project GitHub repo event payload');
		}

		if (messageKey !== String(payload.repoId)) {
			throw new ProjectionContractError(
				`project GitHub repo event key mismatch [key=${messageKey ?? '<missing>'}, repoId=${payload.repoId}]`,
			);
		}

		const eventTime = parseEventTime(payload.occurredAt);
		if (!eventTime) {
			throw new ProjectionContractError('project GitHub repo occurredAt must be RFC3339');
		}

		const {occurredAt, sourceVersion} = eventTime;
		if (payload.eventType === 'DELETE') {
			await this.repository.remove(payload.repoId, occurredAt, sourceVersion);
			return;
		}

		if ((payload.githubRepoUrl === null || payload.githubRepoUrl === undefined) || payload.githubRepoUrl === ''
			|| (payload.owner === null || payload.owner === undefined) || payload.owner === ''
			|| (payload.repo === null || payload.repo === undefined) || payload.repo === '') {
			throw new ProjectionContractError('project GitHub repo UPSERT requires URL, owner, and repo');
		}

		await this.repository.upsert({
			repoId: payload.repoId,
			projectId: payload.projectId,
			teamId: payload.teamId,
			githubRepoUrl: payload.githubRepoUrl,
			owner: payload.owner,
			repo: payload.repo,
			webhookChannelId: payload.webhookChannelId,
			occurredAt,
			sourceVersion,
		});
	}

	private isProjectGithubRepoEvent(payload: unknown): payload is ProjectGithubRepoEvent {
		if (typeof payload !== 'object' || payload === null) {
			return false;
		}

		const event = payload as Partial<ProjectGithubRepoEvent>;
		if (!hasValidRepoEventEnvelope(event)) {
			return false;
		}

		if (event.eventType === 'DELETE') {
			return (event.githubRepoUrl === null || typeof event.githubRepoUrl === 'string')
				&& (event.owner === null || typeof event.owner === 'string')
				&& (event.repo === null || typeof event.repo === 'string');
		}

		return typeof event.githubRepoUrl === 'string'
			&& isLowercaseName(event.owner)
			&& isLowercaseName(event.repo);
	}

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-chat-project-github-repo-event',
			...getKafkaClientConfig(this.configService),
		});
		const stream = PROJECTION_STREAMS.projectGithubRepo;
		this.consumer = kafka.consumer({groupId: stream.groupId});
		await connectWithRetry(this.logger, `Kafka consumer startup: ${stream.topic}`, async () => {
			await this.consumer.connect();
			await this.consumer.subscribe({topic: stream.topic, fromBeginning: true});
			await this.projectionReadiness.registerProjection(kafka, this.consumer, stream);
		});

		void this.consumer.run({
			eachMessage: async ({partition, message}): Promise<void> => {
				await this.projectionReadiness.processMessage(stream, partition, message.offset, async () => applyProjectionMessage(stream, {
					partition, message, readiness: this.projectionReadiness, apply: async (payload, key) => this.handleEvent(payload, key),
				},
				));
			},
		}).catch(async (error: unknown) => {
			this.logger.error('project.github-repo.event Kafka consumer failed', error);
			await this.dicoshot.sendCustom({
				title: '🔴 Kafka Consumer 중단',
				description: 'cowork-chat의 project.github-repo.event consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.',
				color: 'danger',
				fields: [
					{name: 'Topic', value: stream.topic, inline: true},
					...buildErrorFields(error),
				],
			}).catch(() => {/* Alert delivery is best-effort. */});
			// 처리되지 않은 rejection으로 남겨 프로세스를 종료하고 재시작을 맡긴다.
			throw error;
		});
		this.logger.log(`Kafka projection consumer started: ${stream.topic}`);
	}

	async onModuleDestroy() {
		await this.consumer.disconnect();
	}
}

function hasValidRepoEventEnvelope(event: Partial<ProjectGithubRepoEvent>): boolean {
	return event.schemaVersion === 1
		&& (event.eventType === 'UPSERT' || event.eventType === 'DELETE')
		&& isSafePositiveInteger(event.repoId)
		&& isSafePositiveInteger(event.projectId)
		&& isSafePositiveInteger(event.teamId)
		&& (event.webhookChannelId === null || isSafePositiveInteger(event.webhookChannelId))
		&& parseEventTime(event.occurredAt) !== null
		&& (event.snapshot === undefined || typeof event.snapshot === 'boolean');
}

function isLowercaseName(value: unknown): value is string {
	return typeof value === 'string' && value.length > 0 && value === value.toLowerCase();
}
