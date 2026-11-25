import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {isWebhookStateEvent} from '../webhook/webhook-policy.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {WebhookProjectionRepository} from '../repository/index.js';
import {startProjectionConsumer} from '../../common/kafka/projection-consumer.js';
import {type KafkaConsumerHandle} from '../../common/kafka/partition-fetcher.js';

@injectable()
export class WebhookEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(WebhookEventConsumer.name);
	private consumer?: KafkaConsumerHandle;

	constructor(
		private readonly configService: ConfigService,
		private readonly repository: WebhookProjectionRepository,
		private readonly projectionReadiness: ProjectionReadinessService,
	) {}

	private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
		if (!isWebhookStateEvent(payload)) {
			throw new ProjectionContractError('invalid webhook state event payload');
		}

		if (messageKey !== String(payload.webhookId)) {
			throw new ProjectionContractError(
				`webhook state event key mismatch [key=${messageKey ?? '<missing>'}, expected=${payload.webhookId}]`,
			);
		}

		const eventTime = parseEventTime(payload.occurredAt)!;
		await this.repository.upsert({
			webhookId: payload.webhookId,
			executionId: payload.executionId,
			channelId: payload.channelId,
			name: payload.name,
			avatarUrl: payload.avatarUrl,
			isSecure: payload.isSecure,
			secretHash: payload.secretHash,
			isActive: payload.isActive,
			deleted: payload.deleted,
			...eventTime,
		});
	}

	async onModuleInit() {
		const stream = PROJECTION_STREAMS.webhook;
		this.consumer = await startProjectionConsumer({
			logger: this.logger, configService: this.configService, clientId: 'cowork-chat-webhook-event', readiness: this.projectionReadiness, stream,
			handleEvent: async (payload, key) => this.handleEvent(payload, key),
		});
	}

	async onModuleDestroy(): Promise<void> {
		await this.consumer?.close();
	}
}
