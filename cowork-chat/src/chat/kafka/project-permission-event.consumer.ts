import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {mongo} from 'mongoose';
import {DicoshotService} from 'dicoshot-nest';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {buildErrorFields} from '../../common/util/discord-alert.util.js';
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
	// 호환 정리(#596): 2026-11-01 05:00 KST 이후 구형 outbox·Kafka stamp 보정과 rollback 종료 확인 후 누락 허용·ZERO 대체 제거.
	// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
	sourceMembershipVersion?: string;
	// 호환 정리(#596): 2026-11-01 05:00 KST 이후 구형 이벤트·Mongo auth stamp 보정과 rollback 종료 확인 후 nullable fallback 제거.
	// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
	sourceAuthorizationVersion?: Nullable<string>;
	snapshot?: boolean;
};

@injectable()
export class ProjectPermissionEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(ProjectPermissionEventConsumer.name);
	private consumer!: Consumer;

	constructor(
		private readonly configService: ConfigService,
		private readonly dicoshot: DicoshotService,
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
			sourceMembershipVersion: parseEventTime(payload.sourceMembershipVersion)?.sourceVersion ?? mongo.Long.ZERO,
			sourceAuthorizationVersion: parseEventTime(payload.sourceAuthorizationVersion)?.sourceVersion ?? null,
		});
	}

	private isProjectPermissionEvent(payload: unknown): payload is ProjectPermissionEvent {
		if (typeof payload !== 'object' || payload === null) {
			return false;
		}

		const event = payload as Partial<ProjectPermissionEvent>;
		const authorizationVersion = parseEventTime(event.sourceAuthorizationVersion)?.sourceVersion;
		const membershipVersion = parseEventTime(event.sourceMembershipVersion)?.sourceVersion;
		if (event.sourceAuthorizationVersion !== undefined && event.sourceAuthorizationVersion !== null
			&& (!authorizationVersion || !membershipVersion || authorizationVersion.lessThanOrEqual(mongo.Long.ZERO) || authorizationVersion.greaterThan(membershipVersion))) {
			return false;
		}

		return event.eventType === 'UPSERT'
			&& isSafePositiveInteger(event.projectId)
			&& isSafePositiveInteger(event.accountId)
			&& typeof event.read === 'boolean'
			&& typeof event.write === 'boolean'
			&& (event.snapshot === undefined || typeof event.snapshot === 'boolean')
			&& (event.sourceMembershipVersion === undefined || parseEventTime(event.sourceMembershipVersion) !== null)
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
			await this.dicoshot.sendCustom({
				title: '🔴 Kafka Consumer 중단',
				description: `cowork-chat의 ${stream.topic} consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.`,
				color: 'danger',
				fields: [{name: 'Topic', value: stream.topic, inline: true}, ...buildErrorFields(error)],
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
