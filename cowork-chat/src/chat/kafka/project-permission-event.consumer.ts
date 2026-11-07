import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {mongo} from 'mongoose';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {applyProjectionMessage, ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {ProjectPermissionProjectionRepository} from '../repository/index.js';
import {connectWithRetry} from '../../common/kafka/connect-with-retry.js';
import {type Nullable} from '../../common/type/nullable.js';

type ProjectPermissionEvent = {
	eventType: 'UPSERT';
	projectId: number;
	accountId: number;
	read: boolean;
	write: boolean;
	occurredAt: string;
	sourceMembershipVersion: string;
	sourceAuthorizationVersion: Nullable<string>;
	snapshot?: boolean;
};

@injectable()
export class ProjectPermissionEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(ProjectPermissionEventConsumer.name);
	private consumer!: Consumer;

	constructor(
		private readonly configService: ConfigService,
		private readonly permissionRepository: ProjectPermissionProjectionRepository,
		private readonly projectionReadiness: ProjectionReadinessService,
	) {}

	private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
		if (!this.isProjectPermissionEvent(payload)) {
			throw new ProjectionContractError('invalid project permission event payload');
		}

		if (messageKey !== `${payload.projectId}:${payload.accountId}`) {
			throw new ProjectionContractError(
				`project permission event key mismatch [key=${messageKey ?? '<missing>'}, `
				+ `expected=${payload.projectId}:${payload.accountId}]`,
			);
		}

		const eventTime = parseEventTime(payload.occurredAt);
		if (!eventTime) {
			throw new ProjectionContractError('project permission event occurredAt must be RFC3339');
		}

		const {occurredAt, sourceVersion} = eventTime;
		await this.permissionRepository.upsert({
			projectId: payload.projectId,
			userId: payload.accountId,
			canRead: payload.read,
			canWrite: payload.write,
			occurredAt,
			sourceVersion,
			sourceMembershipVersion: parseEventTime(payload.sourceMembershipVersion)!.sourceVersion,
			sourceAuthorizationVersion: payload.sourceAuthorizationVersion === null
				? null
				: parseEventTime(payload.sourceAuthorizationVersion)!.sourceVersion,
		});
	}

	private isProjectPermissionEvent(payload: unknown): payload is ProjectPermissionEvent {
		if (typeof payload !== 'object' || payload === null) {
			return false;
		}

		const event = payload as Partial<ProjectPermissionEvent>;
		const authorizationTime = parseEventTime(event.sourceAuthorizationVersion);
		const membershipTime = parseEventTime(event.sourceMembershipVersion);
		if (event.sourceAuthorizationVersion !== null
			&& (!authorizationTime || !membershipTime
				|| authorizationTime.sourceVersion.lessThanOrEqual(mongo.Long.ZERO)
				|| authorizationTime.sourceVersion.greaterThan(membershipTime.sourceVersion))) {
			return false;
		}

		return (event.sourceAuthorizationVersion !== null || (event.read === false && event.write === false))
			&& event.eventType === 'UPSERT'
			&& isSafePositiveInteger(event.projectId)
			&& isSafePositiveInteger(event.accountId)
			&& typeof event.read === 'boolean'
			&& typeof event.write === 'boolean'
			&& (event.snapshot === undefined || typeof event.snapshot === 'boolean')
			&& membershipTime !== null
			&& parseEventTime(event.occurredAt) !== null;
	}

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-chat-project-permission-event',
			...getKafkaClientConfig(this.configService),
		});
		const stream = PROJECTION_STREAMS.projectPermission;
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
			this.logger.error(`${stream.topic} Kafka consumer failed`, error);
			// 처리되지 않은 rejection으로 남겨 프로세스를 종료하고 재시작을 맡긴다.
			throw error;
		});
		this.logger.log(`Kafka projection consumer started: ${stream.topic}`);
	}

	async onModuleDestroy() {
		await this.consumer.disconnect();
	}
}
