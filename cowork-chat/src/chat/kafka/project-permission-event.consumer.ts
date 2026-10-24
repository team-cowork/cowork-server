import {
    Injectable,
    Logger,
    OnModuleDestroy,
    OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {mongo} from 'mongoose';
import {DicoshotService} from 'dicoshot-nest';
import {getRequiredCsvConfig} from '../../common/config/config.util';
import {parseEventTime} from '../../common/util/event-time.util';
import {buildErrorFields} from '../../common/util/discord-alert.util';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service';
import {applyProjectionMessage, ProjectionContractError} from '../../common/kafka/projection-message.processor';
import {ProjectPermissionProjectionRepository} from '../repository';
import {connectWithRetry} from '../../common/kafka/connect-with-retry';

type ProjectPermissionEvent = {
    eventType: 'UPSERT';
    projectId: number;
    accountId: number;
    read: boolean;
    write: boolean;
    occurredAt: string;
    // eslint-disable-next-line no-warning-comments -- Tracked compatibility cleanup with a deployment-dependent deadline.
    // TODO(#596): 2026-11-01 05:00 KST 이후 구형 outbox·Kafka stamp 보정과 rollback 종료 확인 후 누락 허용·ZERO 대체 제거.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    sourceMembershipVersion?: string;
    snapshot?: boolean;
};

@Injectable()
export class ProjectPermissionEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ProjectPermissionEventConsumer.name);
    private consumer!: Consumer;

    constructor(
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
        private readonly permissionRepository: ProjectPermissionProjectionRepository,
        private readonly projectionReadiness: ProjectionReadinessService,
    ) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-project-permission-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
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
                await this.projectionReadiness.processMessage(stream, partition, message.offset, async () => applyProjectionMessage(
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
                description: `cowork-chat의 ${stream.topic} consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.`,
                color: 'danger',
                fields: [{name: 'Topic', value: stream.topic, inline: true}, ...buildErrorFields(error)],
            }).catch(() => {/* Alert delivery is best-effort. */});
            process.exit(1);
        });
        this.logger.log(`Kafka projection consumer started: ${stream.topic}`);
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

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
        await this.permissionRepository.upsert(
            payload.projectId, payload.accountId, payload.read, payload.write, occurredAt, sourceVersion, parseEventTime(payload.sourceMembershipVersion)?.sourceVersion ?? mongo.Long.ZERO,
        );
    }

    private isProjectPermissionEvent(payload: unknown): payload is ProjectPermissionEvent {
        if (typeof payload !== 'object' || payload === null) {
            return false;
        }

        const event = payload as Partial<ProjectPermissionEvent>;
        return event.eventType === 'UPSERT'
            && isSafePositiveInteger(event.projectId)
            && isSafePositiveInteger(event.accountId)
            && typeof event.read === 'boolean'
            && typeof event.write === 'boolean'
            && (event.snapshot === undefined || typeof event.snapshot === 'boolean')
            && (event.sourceMembershipVersion === undefined || parseEventTime(event.sourceMembershipVersion) !== null)
            && parseEventTime(event.occurredAt) !== null;
    }
}
