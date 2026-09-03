import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { Server } from 'socket.io';
import { DicoshotService } from 'dicoshot-nest';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { buildErrorFields } from '../../common/util/discord-alert.util';
import { isSafePositiveInteger } from '../../common/util/safe-integer.util';
import { parseEventTime } from '../../common/util/event-time.util';
import { PROJECTION_STREAMS, ProjectionReadinessService } from '../../common/kafka/projection-readiness.service';
import { applyProjectionMessage, ProjectionContractError } from '../../common/kafka/projection-message.processor';
import { ProjectMemberProjectionRepository } from '../repository/project-member-projection.repository';
import { ProjectProjectionRepository } from '../repository/project-projection.repository';
import { ChannelMessageReadAccessService } from '../service/channel-message-read-access.service';

interface ProjectEvent {
    eventType: 'CREATED' | 'UPDATED' | 'DELETED';
    projectId: number;
    teamId: number;
    name: string;
    description: string | null;
    status: string;
    position?: number;
    occurredAt: string;
    /** 주기/startup state replay이면 변경 알림 부수효과를 억제한다. */
    snapshot?: boolean;
}

@Injectable()
export class ProjectEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ProjectEventConsumer.name);
    private consumer!: Consumer;
    private io?: Server;

    constructor(
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
        private readonly projectRepository: ProjectProjectionRepository,
        private readonly projectMemberRepository: ProjectMemberProjectionRepository,
        private readonly projectionReadiness: ProjectionReadinessService,
        private readonly channelMessageReadAccess: ChannelMessageReadAccessService,
    ) {}

    setSocketServer(io: Server) {
        this.io = io;
    }

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-project-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        const stream = PROJECTION_STREAMS.project;
        this.consumer = kafka.consumer({ groupId: stream.groupId });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: stream.topic, fromBeginning: true });
        await this.projectionReadiness.registerProjection(kafka, this.consumer, stream);

        void this.consumer
            .run({
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
            })
            .catch(async (err) => {
                this.logger.error('project.event Kafka consumer failed', err);
                await this.dicoshot.sendCustom({
                    title: '🔴 Kafka Consumer 중단',
                    description: 'cowork-chat의 project.event consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.',
                    color: 'danger',
                    fields: [
                        { name: 'Topic', value: 'project.event', inline: true },
                        ...buildErrorFields(err),
                    ],
                }).catch(() => {});
                process.exit(1);
            });
        this.logger.log('Kafka consumer started: project.event');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
        if (!this.isProjectEvent(payload)) {
            throw new ProjectionContractError('invalid project event payload');
        }
        const event = payload;
        if (messageKey !== String(event.projectId)) {
            throw new ProjectionContractError(
                `project event key does not match projectId [key=${messageKey ?? '<missing>'}, projectId=${event.projectId}]`,
            );
        }
        const eventTime = parseEventTime(event.occurredAt);
        if (!eventTime) throw new ProjectionContractError('project event occurredAt must be RFC3339');
        const { occurredAt, sourceVersion } = eventTime;

        const applied = event.eventType === 'DELETED'
            ? await this.projectRepository.remove(event.projectId, occurredAt, sourceVersion)
            : await this.projectRepository.upsert({
                projectId: event.projectId,
                teamId: event.teamId,
                name: event.name,
                description: event.description,
                status: event.status,
                position: event.position ?? 0,
                occurredAt,
                sourceVersion,
            });
        if (!applied) return;

        if (event.eventType === 'DELETED') {
            await this.projectMemberRepository.removeByProjectId(event.projectId, occurredAt, sourceVersion);
        }
        if (event.snapshot === true) return;

        if (!this.io) return;
        const { eventType, snapshot, ...projectionPayload } = event;
        void snapshot;
        if (eventType === 'CREATED') {
            await this.channelMessageReadAccess.emitToActiveTeamUsers(
                this.io, event.teamId, 'project:created', projectionPayload,
            );
        } else if (eventType === 'UPDATED') {
            await this.channelMessageReadAccess.emitToActiveTeamUsers(
                this.io, event.teamId, 'project:updated', projectionPayload,
            );
        } else if (eventType === 'DELETED') {
            await this.channelMessageReadAccess.emitToActiveTeamUsers(
                this.io,
                event.teamId,
                'project:deleted',
                { projectId: event.projectId, teamId: event.teamId },
            );
        }
    }

    private isProjectEvent(payload: unknown): payload is ProjectEvent {
        if (typeof payload !== 'object' || payload === null) return false;
        const event = payload as Partial<ProjectEvent>;
        if ((event.eventType !== 'CREATED' && event.eventType !== 'UPDATED' && event.eventType !== 'DELETED')
            || !isSafePositiveInteger(event.projectId)
            || !isSafePositiveInteger(event.teamId)
            || (event.snapshot !== undefined && typeof event.snapshot !== 'boolean')
            || parseEventTime(event.occurredAt) === null) {
            return false;
        }
        if (event.eventType === 'DELETED') return true;
        return typeof event.name === 'string'
            && (event.description === null || typeof event.description === 'string')
            && typeof event.status === 'string'
            && (event.position === undefined || typeof event.position === 'number');
    }
}
