import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Consumer, Kafka } from 'kafkajs';
import { Server } from 'socket.io';
import { DicoshotService } from 'dicoshot-nest';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { parseEventTime } from '../../common/util/event-time.util';
import { buildErrorFields } from '../../common/util/discord-alert.util';
import { isSafePositiveInteger } from '../../common/util/safe-integer.util';
import { PROJECTION_STREAMS, ProjectionReadinessService } from '../../common/kafka/projection-readiness.service';
import { applyProjectionMessage, ProjectionContractError } from '../../common/kafka/projection-message.processor';
import { TeamMemberProjectionRepository } from '../repository/team-member-projection.repository';
import { ChannelProjectionRepository } from '../repository/channel-projection.repository';
import { ChannelMessageReadAccessService } from '../service/channel-message-read-access.service';

interface TeamMemberEvent {
    eventType: 'UPSERT' | 'DELETE';
    teamId: number;
    userId: number;
    role: string;
    teamName: string;
    occurredAt: string;
    snapshot?: boolean;
}

@Injectable()
export class TeamMemberEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(TeamMemberEventConsumer.name);
    private consumer!: Consumer;
    private io?: Server;

    constructor(
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
        private readonly memberRepository: TeamMemberProjectionRepository,
        private readonly projectionReadiness: ProjectionReadinessService,
        private readonly channelRepository: ChannelProjectionRepository,
        private readonly channelMessageReadAccess: ChannelMessageReadAccessService,
    ) {}

    setSocketServer(io: Server): void {
        this.io = io;
    }

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-team-member-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        const stream = PROJECTION_STREAMS.teamMember;
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
            this.logger.error('team.member.event Kafka consumer failed', err);
            await this.dicoshot.sendCustom({
                title: '🔴 Kafka Consumer 중단',
                description: 'cowork-chat의 team.member.event consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.',
                color: 'danger',
                fields: [{ name: 'Topic', value: 'team.member.event', inline: true }, ...buildErrorFields(err)],
            }).catch(() => {});
            process.exit(1);
        });
        this.logger.log('Kafka projection consumer started: team.member.event');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
        if (!this.isTeamMemberEvent(payload)) {
            throw new ProjectionContractError('invalid team member event payload');
        }
        const expectedKey = `${payload.teamId}:${payload.userId}`;
        if (messageKey !== expectedKey) {
            throw new ProjectionContractError(
                `team member event key mismatch [key=${messageKey ?? '<missing>'}, expected=${expectedKey}]`,
            );
        }
        const eventTime = parseEventTime(payload.occurredAt);
        if (!eventTime) throw new ProjectionContractError('team member event occurredAt must be RFC3339');
        const { occurredAt, sourceVersion } = eventTime;
        if (payload.eventType === 'DELETE') {
            await this.memberRepository.remove(payload.teamId, payload.userId, occurredAt, sourceVersion);
        } else {
            await this.memberRepository.upsert({
                teamId: payload.teamId,
                userId: payload.userId,
                role: payload.role,
                teamName: payload.teamName,
                occurredAt,
                sourceVersion,
            });
        }
        if (!this.io) return;

        const channelIds = await this.channelRepository.findIdsByTeamId(payload.teamId);
        await this.channelMessageReadAccess.evictUnauthorizedSockets(this.io, channelIds, [payload.userId]);
        if (payload.eventType === 'DELETE' && !(await this.memberRepository.exists(payload.teamId, payload.userId))) {
            this.io.in(`user:${payload.userId}`).socketsLeave(`team:${payload.teamId}`);
            if (payload.snapshot !== true) {
                this.io.to(`user:${payload.userId}`).emit('team:access:revoked', { teamId: payload.teamId });
            }
        }
    }

    private isTeamMemberEvent(payload: unknown): payload is TeamMemberEvent {
        if (typeof payload !== 'object' || payload === null) return false;
        const event = payload as Partial<TeamMemberEvent>;
        if ((event.eventType !== 'UPSERT' && event.eventType !== 'DELETE')
            || !isSafePositiveInteger(event.teamId)
            || !isSafePositiveInteger(event.userId)
            || (event.snapshot !== undefined && typeof event.snapshot !== 'boolean')
            || parseEventTime(event.occurredAt) === null) {
            return false;
        }
        if (event.eventType === 'DELETE') return true;
        return typeof event.role === 'string' && event.role.length > 0
            && typeof event.teamName === 'string' && event.teamName.length > 0;
    }
}
