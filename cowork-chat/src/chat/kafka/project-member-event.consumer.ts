import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Consumer, Kafka } from 'kafkajs';
import { DicoshotService } from 'dicoshot-nest';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { parseEventTime } from '../../common/util/event-time.util';
import { buildErrorFields } from '../../common/util/discord-alert.util';
import { isSafePositiveInteger } from '../../common/util/safe-integer.util';
import { PROJECTION_STREAMS, ProjectionReadinessService } from '../../common/kafka/projection-readiness.service';
import { applyProjectionMessage, ProjectionContractError } from '../../common/kafka/projection-message.processor';
import { ProjectMemberProjectionRepository } from '../repository/project-member-projection.repository';

interface ProjectMemberEvent {
    eventType: 'ADDED' | 'REMOVED';
    projectId: number;
    userId: number;
    occurredAt: string;
    snapshot?: boolean;
}

@Injectable()
export class ProjectMemberEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ProjectMemberEventConsumer.name);
    private consumer!: Consumer;

    constructor(
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
        private readonly memberRepository: ProjectMemberProjectionRepository,
        private readonly projectionReadiness: ProjectionReadinessService,
    ) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-project-member-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        const stream = PROJECTION_STREAMS.projectMember;
        this.consumer = kafka.consumer({ groupId: stream.groupId });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: stream.topic, fromBeginning: true });
        await this.projectionReadiness.registerProjection(kafka, this.consumer, stream);

        void this.consumer.run({
            eachMessage: async ({ partition, message }): Promise<void> => {
                await this.projectionReadiness.processMessage(stream, partition, message.offset, async () => {
                    return applyProjectionMessage(
                        stream,
                        partition,
                        message,
                        this.projectionReadiness,
                        (payload, key) => this.handleEvent(payload, key),
                    );
                });
            },
        }).catch(async (err) => {
            this.logger.error('project.member.event Kafka consumer failed', err);
            await this.dicoshot.sendCustom({
                title: '🔴 Kafka Consumer 중단',
                description: 'cowork-chat의 project.member.event consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.',
                color: 'danger',
                fields: [{ name: 'Topic', value: 'project.member.event', inline: true }, ...buildErrorFields(err)],
            }).catch(() => {});
            process.exit(1);
        });
        this.logger.log('Kafka projection consumer started: project.member.event');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
        if (!this.isProjectMemberEvent(payload)) {
            throw new ProjectionContractError('invalid project member event payload');
        }
        const expectedKey = `${payload.projectId}:${payload.userId}`;
        if (messageKey !== expectedKey) {
            throw new ProjectionContractError(
                `project member event key mismatch [key=${messageKey ?? '<missing>'}, expected=${expectedKey}]`,
            );
        }
        const eventTime = parseEventTime(payload.occurredAt);
        if (!eventTime) throw new ProjectionContractError('project member event occurredAt must be RFC3339');
        const { occurredAt, sourceVersion } = eventTime;
        if (payload.eventType === 'ADDED') {
            await this.memberRepository.add(payload.projectId, payload.userId, occurredAt, sourceVersion);
        } else {
            await this.memberRepository.remove(payload.projectId, payload.userId, occurredAt, sourceVersion);
        }
    }

    private isProjectMemberEvent(payload: unknown): payload is ProjectMemberEvent {
        if (typeof payload !== 'object' || payload === null) return false;
        const event = payload as Partial<ProjectMemberEvent>;
        return (event.eventType === 'ADDED' || event.eventType === 'REMOVED')
            && isSafePositiveInteger(event.projectId)
            && isSafePositiveInteger(event.userId)
            && (event.snapshot === undefined || typeof event.snapshot === 'boolean')
            && parseEventTime(event.occurredAt) !== null;
    }
}
