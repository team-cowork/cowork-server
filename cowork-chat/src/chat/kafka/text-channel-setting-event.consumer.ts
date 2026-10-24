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
import {buildErrorFields} from '../../common/util/discord-alert.util';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service';
import {applyProjectionMessage, ProjectionContractError} from '../../common/kafka/projection-message.processor';
import {TextChannelSettingProjectionRepository} from '../repository';
import {connectWithRetry} from '../../common/kafka/connect-with-retry';

const MAX_SLOWMODE_SECONDS = 21_600;

type TextChannelSettingEvent = {
    schemaVersion: 1;
    eventType: 'UPSERT';
    channelId: number;
    slowmodeSeconds: number;
    occurredAt: string;
    snapshot?: boolean;
};

@Injectable()
export class TextChannelSettingEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(TextChannelSettingEventConsumer.name);
    private consumer!: Consumer;

    constructor(
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
        private readonly repository: TextChannelSettingProjectionRepository,
        private readonly projectionReadiness: ProjectionReadinessService,
    ) {}

    async onModuleInit(): Promise<void> {
        const kafka = new Kafka({
            clientId: 'cowork-chat-text-channel-setting-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        const stream = PROJECTION_STREAMS.textChannelSetting;
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
