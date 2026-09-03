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
import { ChannelRolePolicyProjectionRepository } from '../repository/channel-role-policy-projection.repository';
import { ChannelMessageReadAccessService } from '../service/channel-message-read-access.service';

interface ChannelRolePolicyEvent {
    schemaVersion: 1;
    eventType: 'UPSERT' | 'DELETE';
    teamId: number;
    channelId: number;
    roleId: number;
    permissions: { message_read: boolean } | null;
    occurredAt: string;
    snapshot?: boolean;
}

@Injectable()
export class ChannelRolePolicyEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChannelRolePolicyEventConsumer.name);
    private consumer!: Consumer;
    private io?: Server;

    constructor(
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
        private readonly repository: ChannelRolePolicyProjectionRepository,
        private readonly accessService: ChannelMessageReadAccessService,
        private readonly projectionReadiness: ProjectionReadinessService,
    ) {}

    setSocketServer(io: Server): void {
        this.io = io;
    }

    async onModuleInit(): Promise<void> {
        const kafka = new Kafka({
            clientId: 'cowork-chat-channel-role-policy-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        const stream = PROJECTION_STREAMS.channelRolePolicy;
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
        if (!eventTime) throw new ProjectionContractError('channel role policy occurredAt must be RFC3339');
        if (payload.eventType === 'UPSERT') {
            await this.repository.upsert({
                teamId: payload.teamId,
                channelId: payload.channelId,
                roleId: payload.roleId,
                messageRead: payload.permissions!.message_read,
                ...eventTime,
            });
        } else {
            await this.repository.remove(
                payload.teamId,
                payload.channelId,
                payload.roleId,
                eventTime.occurredAt,
                eventTime.sourceVersion,
            );
        }
        if (this.io) {
            await this.accessService.evictUnauthorizedSockets(this.io, [payload.channelId]);
        }
    }

    private isChannelRolePolicyEvent(payload: unknown): payload is ChannelRolePolicyEvent {
        if (typeof payload !== 'object' || payload === null) return false;
        const event = payload as Partial<ChannelRolePolicyEvent>;
        if (event.schemaVersion !== 1
            || (event.eventType !== 'UPSERT' && event.eventType !== 'DELETE')
            || !isSafePositiveInteger(event.teamId)
            || !isSafePositiveInteger(event.channelId)
            || !isSafePositiveInteger(event.roleId)
            || parseEventTime(event.occurredAt) === null
            || (event.snapshot !== undefined && typeof event.snapshot !== 'boolean')) return false;
        if (event.eventType === 'DELETE') return event.permissions === null;
        if (typeof event.permissions !== 'object' || event.permissions === null) return false;
        const permissions = event.permissions as Record<string, unknown>;
        return Object.keys(permissions).length === 1 && typeof permissions.message_read === 'boolean';
    }
}
