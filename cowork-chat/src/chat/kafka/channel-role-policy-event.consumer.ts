import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
	Inject as inject,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {Server} from 'socket.io';
import {DicoshotService} from 'dicoshot-nest';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {buildErrorFields} from '../../common/util/discord-alert.util.js';
import {isBooleanRecord} from '../../common/util/boolean-record.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {applyProjectionMessage, ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {ChannelRolePolicyProjectionRepository} from '../repository/index.js';
import {ChannelMessageReadAccessService} from '../service/index.js';
import {connectWithRetry} from '../../common/kafka/connect-with-retry.js';
import {type Nullable} from '../../common/type/nullable.js';

type ChannelRolePolicyEvent = {
	schemaVersion: 2;
	eventType: 'UPSERT' | 'DELETE';
	teamId: number;
	channelId: number;
	roleId: number;
	permissions: Nullable<Record<string, boolean>>;
	occurredAt: string;
	snapshot?: boolean;
};

@injectable()
export class ChannelRolePolicyEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(ChannelRolePolicyEventConsumer.name);
	private consumer!: Consumer;
	private io?: Server;

	@inject()
	private readonly configService!: ConfigService;

	@inject()
	private readonly dicoshot!: DicoshotService;

	@inject()
	private readonly repository!: ChannelRolePolicyProjectionRepository;

	@inject()
	private readonly accessService!: ChannelMessageReadAccessService;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
		if (!this.isChannelRolePolicyEvent(payload)) {
			throw new ProjectionContractError('invalid channel role policy event payload');
		}

		const expectedKey = `policy:${payload.teamId}:${payload.channelId}:${payload.roleId}`;
		if (messageKey !== expectedKey) {
			throw new ProjectionContractError(
				`channel role policy event key mismatch [key=${messageKey ?? '<missing>'}, expected=${expectedKey}]`,
			);
		}

		const eventTime = parseEventTime(payload.occurredAt);
		if (!eventTime) {
			throw new ProjectionContractError('channel role policy occurredAt must be RFC3339');
		}

		if (payload.eventType === 'UPSERT') {
			await this.repository.upsert({
				teamId: payload.teamId,
				channelId: payload.channelId,
				roleId: payload.roleId,
				permissions: payload.permissions!,
				...eventTime,
			});
		} else {
			await this.repository.remove({
				teamId: payload.teamId, channelId: payload.channelId, roleId: payload.roleId, occurredAt: eventTime.occurredAt, sourceVersion: eventTime.sourceVersion,
			});
		}

		if (this.io) {
			await this.accessService.evictUnauthorizedSockets(this.io, [payload.channelId]);
		}
	}

	private isChannelRolePolicyEvent(payload: unknown): payload is ChannelRolePolicyEvent {
		if (typeof payload !== 'object' || payload === null) {
			return false;
		}

		const event = payload as Partial<ChannelRolePolicyEvent>;
		if (event.schemaVersion !== 2
			|| (event.eventType !== 'UPSERT' && event.eventType !== 'DELETE')
			|| !isSafePositiveInteger(event.teamId)
			|| !isSafePositiveInteger(event.channelId)
			|| !isSafePositiveInteger(event.roleId)
			|| parseEventTime(event.occurredAt) === null
			|| (event.snapshot !== undefined && typeof event.snapshot !== 'boolean')) {
			return false;
		}

		return event.eventType === 'DELETE'
			? event.permissions === null
			: isBooleanRecord(event.permissions) && Object.keys(event.permissions).length > 0;
	}

	setSocketServer(io: Server): void {
		this.io = io;
	}

	async onModuleInit(): Promise<void> {
		const kafka = new Kafka({
			clientId: 'cowork-chat-channel-role-policy-event',
			...getKafkaClientConfig(this.configService),
		});
		const stream = PROJECTION_STREAMS.channelRolePolicy;
		this.consumer = kafka.consumer({groupId: stream.groupId});
		await connectWithRetry(this.logger, `Kafka consumer startup: ${stream.topic}`, async () => {
			await this.consumer.connect();
			await this.consumer.subscribe({topic: stream.topic, fromBeginning: true});
			await this.projectionReadiness.registerProjection(kafka, this.consumer, stream);
		});

		void this.consumer.run({
			eachMessage: async ({partition, message}): Promise<void> => {
				await this.projectionReadiness.processMessage(stream, partition, message.offset, async () =>
					applyProjectionMessage(stream, {
						partition, message, readiness: this.projectionReadiness, apply: async (payload, key) => this.handleEvent(payload, key),
					},
					));
			},
		}).catch(async (error: unknown) => {
			this.logger.error(`${stream.topic} Kafka consumer failed`, error);
			await this.dicoshot.sendCustom({
				title: '🔴 Kafka Consumer 중단',
				description: `cowork-chat의 ${stream.topic} consumer가 종료되어 프로세스를 재시작합니다.`,
				color: 'danger',
				fields: [{name: 'Topic', value: stream.topic, inline: true}, ...buildErrorFields(error)],
			}).catch(() => {/* Alert delivery is best-effort. */});
			// 처리되지 않은 rejection으로 남겨 프로세스를 종료하고 재시작을 맡긴다.
			throw error;
		});
		this.logger.log(`Kafka projection consumer started: ${stream.topic}`);
	}

	async onModuleDestroy(): Promise<void> {
		await this.consumer.disconnect();
	}
}
