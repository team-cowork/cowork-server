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
import { TeamRoleProjectionRepository } from '../repository/team-role-projection.repository';
import { ChannelRolePolicyProjectionRepository } from '../repository/channel-role-policy-projection.repository';
import { ChannelMessageReadAccessService } from '../service/channel-message-read-access.service';

interface TeamRoleEvent {
    eventType: 'ROLE_UPSERTED' | 'ROLE_DELETED' | 'ASSIGNMENT_UPSERTED' | 'ASSIGNMENT_DELETED' | 'MEMBER_ASSIGNMENTS_DELETED';
    teamId: number;
    roleId?: number;
    accountId?: number;
    name?: string;
    priority?: number;
    permissions?: string[];
    occurredAt: string;
}

@Injectable()
export class TeamRoleEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(TeamRoleEventConsumer.name);
    private consumer!: Consumer;
    private io?: Server;

    constructor(
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
        private readonly repository: TeamRoleProjectionRepository,
        private readonly policyRepository: ChannelRolePolicyProjectionRepository,
        private readonly accessService: ChannelMessageReadAccessService,
        private readonly projectionReadiness: ProjectionReadinessService,
    ) {}

    setSocketServer(io: Server): void {
        this.io = io;
    }

    async onModuleInit(): Promise<void> {
        const kafka = new Kafka({
            clientId: 'cowork-chat-team-role-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        const stream = PROJECTION_STREAMS.teamRole;
        this.consumer = kafka.consumer({ groupId: stream.groupId });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: stream.topic, fromBeginning: true });
        await this.projectionReadiness.registerProjection(kafka, this.consumer, stream);

        void this.consumer.run({
            eachMessage: async ({ partition, message }): Promise<void> => {
                await this.projectionReadiness.processMessage(stream, partition, message.offset, async () =>
                    applyProjectionMessage(
                        stream,
                        partition,
                        message,
                        this.projectionReadiness,
                        (payload, key) => this.handleEvent(payload, key),
                    ));
            },
        }).catch(async (error: unknown) => {
            this.logger.error(`${stream.topic} Kafka consumer failed`, error);
            await this.dicoshot.sendCustom({
                title: '🔴 Kafka Consumer 중단',
                description: `cowork-chat의 ${stream.topic} consumer가 종료되어 프로세스를 재시작합니다.`,
                color: 'danger',
                fields: [{ name: 'Topic', value: stream.topic, inline: true }, ...buildErrorFields(error)],
            }).catch(() => {});
            process.exit(1);
        });
        this.logger.log(`Kafka projection consumer started: ${stream.topic}`);
    }

    async onModuleDestroy(): Promise<void> {
        await this.consumer.disconnect();
    }

    private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
        if (!this.isTeamRoleEvent(payload)) throw new ProjectionContractError('invalid team role event payload');
        this.assertKey(payload, messageKey);
        const eventTime = parseEventTime(payload.occurredAt);
        if (!eventTime) throw new ProjectionContractError('team role event occurredAt must be RFC3339');

        let affectedChannelIds: number[];
        let affectedUserIds: number[] | undefined;
        if (payload.eventType === 'ROLE_UPSERTED') {
            await this.repository.upsertRole({
                teamId: payload.teamId,
                roleId: payload.roleId!,
                name: payload.name!,
                priority: payload.priority!,
                permissions: payload.permissions!,
                ...eventTime,
            });
            affectedChannelIds = await this.policyRepository.findChannelIdsByRole(payload.teamId, payload.roleId!);
        } else if (payload.eventType === 'ROLE_DELETED') {
            await this.repository.deleteRole(payload.teamId, payload.roleId!, eventTime.occurredAt, eventTime.sourceVersion);
            affectedChannelIds = await this.policyRepository.findChannelIdsByRole(payload.teamId, payload.roleId!);
        } else if (payload.eventType === 'ASSIGNMENT_UPSERTED') {
            await this.repository.upsertAssignment({
                teamId: payload.teamId,
                accountId: payload.accountId!,
                roleId: payload.roleId!,
                ...eventTime,
            });
            affectedChannelIds = await this.policyRepository.findChannelIdsByRole(payload.teamId, payload.roleId!);
            affectedUserIds = [payload.accountId!];
        } else if (payload.eventType === 'ASSIGNMENT_DELETED') {
            await this.repository.deleteAssignment(
                payload.teamId,
                payload.accountId!,
                payload.roleId!,
                eventTime.occurredAt,
                eventTime.sourceVersion,
            );
            affectedChannelIds = await this.policyRepository.findChannelIdsByRole(payload.teamId, payload.roleId!);
            affectedUserIds = [payload.accountId!];
        } else {
            affectedChannelIds = await this.policyRepository.findChannelIdsByTeam(payload.teamId);
            await this.repository.deleteMemberAssignments(
                payload.teamId,
                payload.accountId!,
                eventTime.occurredAt,
                eventTime.sourceVersion,
            );
            affectedUserIds = [payload.accountId!];
        }
        if (this.io && affectedChannelIds.length > 0) {
            await this.accessService.evictUnauthorizedSockets(this.io, affectedChannelIds, affectedUserIds);
        }
    }

    private isTeamRoleEvent(payload: unknown): payload is TeamRoleEvent {
        if (typeof payload !== 'object' || payload === null) return false;
        const event = payload as Partial<TeamRoleEvent>;
        if (!['ROLE_UPSERTED', 'ROLE_DELETED', 'ASSIGNMENT_UPSERTED', 'ASSIGNMENT_DELETED', 'MEMBER_ASSIGNMENTS_DELETED']
            .includes(event.eventType ?? '')
            || !isSafePositiveInteger(event.teamId)
            || parseEventTime(event.occurredAt) === null) return false;
        if (event.eventType === 'ROLE_UPSERTED') {
            return isSafePositiveInteger(event.roleId)
                && typeof event.name === 'string' && event.name.length > 0
                && Number.isSafeInteger(event.priority)
                && Array.isArray(event.permissions)
                && event.permissions.every((permission) => typeof permission === 'string' && permission.length > 0);
        }
        if (event.eventType === 'ROLE_DELETED') return isSafePositiveInteger(event.roleId);
        if (event.eventType === 'MEMBER_ASSIGNMENTS_DELETED') return isSafePositiveInteger(event.accountId);
        return isSafePositiveInteger(event.roleId) && isSafePositiveInteger(event.accountId);
    }

    private assertKey(event: TeamRoleEvent, messageKey?: string): void {
        let expected: string;
        if (event.eventType === 'ROLE_UPSERTED' || event.eventType === 'ROLE_DELETED') {
            expected = `role:${event.teamId}:${event.roleId}`;
        } else if (event.eventType === 'MEMBER_ASSIGNMENTS_DELETED') {
            expected = `member:${event.teamId}:${event.accountId}`;
        } else {
            expected = `assignment:${event.teamId}:${event.accountId}:${event.roleId}`;
        }
        if (messageKey !== expected) {
            throw new ProjectionContractError(
                `team role event key mismatch [key=${messageKey ?? '<missing>'}, expected=${expected}]`,
            );
        }
    }
}
