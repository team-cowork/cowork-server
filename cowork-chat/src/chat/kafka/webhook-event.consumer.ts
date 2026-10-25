import {
    Injectable,
    Logger,
    OnModuleDestroy,
    OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {DicoshotService} from 'dicoshot-nest';
import {getRequiredCsvConfig} from '../../common/config/config.util';
import {parseEventTime} from '../../common/util/event-time.util';
import {isWebhookStateEvent} from '../webhook/webhook-policy';
import {buildErrorFields} from '../../common/util/discord-alert.util';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service';
import {applyProjectionMessage, ProjectionContractError} from '../../common/kafka/projection-message.processor';
import {WebhookProjectionRepository} from '../repository';
import {connectWithRetry} from '../../common/kafka/connect-with-retry';

@Injectable()
export class WebhookEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(WebhookEventConsumer.name);
    private consumer!: Consumer;

    constructor(
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
        private readonly repository: WebhookProjectionRepository,
        private readonly projectionReadiness: ProjectionReadinessService,
    ) {}

    async onModuleInit(): Promise<void> {
        const kafka = new Kafka({
            clientId: 'cowork-chat-webhook-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        const stream = PROJECTION_STREAMS.webhook;
        this.consumer = kafka.consumer({groupId: stream.groupId});
        await connectWithRetry(this.logger, `Kafka consumer startup: ${stream.topic}`, async () => {
            await this.consumer.connect();
            await this.consumer.subscribe({topic: stream.topic, fromBeginning: true});
            await this.projectionReadiness.registerProjection(kafka, this.consumer, stream);
        });

        void this.consumer.run({
            eachMessage: async ({partition, message}): Promise<void> => {
                await this.projectionReadiness.processMessage(stream, partition, message.offset, async () =>
                    applyProjectionMessage(
                        stream,
                        partition,
                        message,
                        this.projectionReadiness,
                        async (payload, key) => this.handleEvent(payload, key),
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
            process.exit(1);
        });
        this.logger.log(`Kafka projection consumer started: ${stream.topic}`);
    }

    async onModuleDestroy(): Promise<void> {
        await this.consumer.disconnect();
    }

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
}
