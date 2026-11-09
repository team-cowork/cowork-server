import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {TextChannelSettingProjectionRepository} from '../repository/index.js';
import {startProjectionConsumer} from '../../common/kafka/projection-consumer.js';

const MAX_SLOWMODE_SECONDS = 21_600;

type TextChannelSettingEvent = {
	schemaVersion: 1;
	eventType: 'UPSERT';
	channelId: number;
	slowmodeSeconds: number;
	occurredAt: string;
	snapshot?: boolean;
};

@injectable()
export class TextChannelSettingEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(TextChannelSettingEventConsumer.name);
	private consumer!: Consumer;

	constructor(
		private readonly configService: ConfigService,
		private readonly repository: TextChannelSettingProjectionRepository,
		private readonly projectionReadiness: ProjectionReadinessService,
	) {}

	private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
		if (!isTextChannelSettingEvent(payload)) {
			throw new ProjectionContractError('invalid text channel setting event payload');
		}

		if (messageKey !== String(payload.channelId)) {
			throw new ProjectionContractError(
				`text channel setting event key mismatch [key=${messageKey ?? '<missing>'}, expected=${payload.channelId}]`,
			);
		}

		const eventTime = parseEventTime(payload.occurredAt)!;
		await this.repository.upsert({
			channelId: payload.channelId,
			slowmodeSeconds: payload.slowmodeSeconds,
			...eventTime,
		});
	}

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-chat-text-channel-setting-event',
			...getKafkaClientConfig(this.configService),
		});
		const stream = PROJECTION_STREAMS.textChannelSetting;
		this.consumer = kafka.consumer({groupId: stream.groupId});
		await startProjectionConsumer({
			logger: this.logger, kafka, consumer: this.consumer, readiness: this.projectionReadiness, stream,
			handleEvent: async (payload, key) => this.handleEvent(payload, key),
		});
	}

	async onModuleDestroy(): Promise<void> {
		await this.consumer.disconnect();
	}
}

export function isTextChannelSettingEvent(payload: unknown): payload is TextChannelSettingEvent {
	if (typeof payload !== 'object' || payload === null) {
		return false;
	}

	const event = payload as Partial<TextChannelSettingEvent>;
	return event.schemaVersion === 1
		&& event.eventType === 'UPSERT'
		&& isSafePositiveInteger(event.channelId)
		&& Number.isSafeInteger(event.slowmodeSeconds)
		&& event.slowmodeSeconds! >= 0
		&& event.slowmodeSeconds! <= MAX_SLOWMODE_SECONDS
		&& parseEventTime(event.occurredAt) !== null
		&& (event.snapshot === undefined || typeof event.snapshot === 'boolean');
}
