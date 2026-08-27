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
import { ChannelProjectionEvent, ChannelProjectionRepository } from '../repository/channel-projection.repository';

interface ChannelEvent {
    eventType: 'CREATED' | 'UPDATED' | 'DELETED';
    channelId: number;
    teamId: number | null;
    /** 팀 채널은 null, 프로젝트 채널은 해당 project ID. legacy 이벤트는 누락될 수 있다. */
    projectId?: number | null;
    name: string;
    type: string;
    viewType: string;
    description: string | null;
    isPrivate: boolean;
    occurredAt: string;
    /** 주기/startup state replay이면 변경 알림 부수효과를 억제한다. */
    snapshot?: boolean;
    /** 이전 producer 이벤트와의 호환을 위해 선택값이며, 누락 시 0으로 저장한다. */
    position?: number;
}

@Injectable()
export class ChannelEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChannelEventConsumer.name);
    private consumer!: Consumer;
    private io?: Server;

    constructor(
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
        private readonly channelRepository: ChannelProjectionRepository,
        private readonly projectionReadiness: ProjectionReadinessService,
    ) {}

    setSocketServer(io: Server) {
        this.io = io;
    }

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-channel-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        const stream = PROJECTION_STREAMS.channel;
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
            this.logger.error('channel.event Kafka consumer failed', err);
            await this.dicoshot.sendCustom({
                title: '🔴 Kafka Consumer 중단',
                description: 'cowork-chat의 channel.event consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.',
                color: 'danger',
                fields: [{ name: 'Topic', value: 'channel.event', inline: true }, ...buildErrorFields(err)],
            }).catch(() => {});
            process.exit(1);
        });
        this.logger.log('Kafka projection consumer started: channel.event');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
        if (!this.isChannelEvent(payload)) {
            throw new ProjectionContractError('invalid channel event payload');
        }
        const event = payload;
        if (messageKey !== String(event.channelId)) {
            throw new ProjectionContractError(
                `channel event key does not match channelId [key=${messageKey ?? '<missing>'}, channelId=${event.channelId}]`,
            );
        }

        const eventTime = parseEventTime(event.occurredAt);
        if (!eventTime) throw new ProjectionContractError('channel event occurredAt must be RFC3339');
        const { occurredAt, sourceVersion } = eventTime;
        let applied: boolean;
        if (event.eventType === 'DELETED') {
            applied = await this.channelRepository.remove(event.channelId, occurredAt, sourceVersion);
        } else {
            const projectionEvent: ChannelProjectionEvent = {
                eventType: event.eventType,
                channelId: event.channelId,
                teamId: event.teamId,
                projectId: event.projectId ?? null,
                name: event.name,
                type: event.type,
                viewType: event.viewType,
                description: event.description,
                isPrivate: event.isPrivate,
                position: event.position ?? 0,
                occurredAt,
                sourceVersion,
            };
            applied = await this.channelRepository.upsert(projectionEvent);
        }
        if (applied === false) return;
        if (event.snapshot === true) return;

        if (event.teamId === null || !this.io) return;
        const room = `team:${event.teamId}`;
        const { eventType, snapshot, ...projectionPayload } = event;
        void snapshot;
        if (eventType === 'CREATED') {
            this.io.to(room).emit('channel:created', projectionPayload);
        } else if (eventType === 'UPDATED') {
            this.io.to(room).emit('channel:updated', projectionPayload);
        } else {
            this.io.to(room).emit('channel:deleted', { channelId: event.channelId, teamId: event.teamId });
        }
    }

    private isChannelEvent(payload: unknown): payload is ChannelEvent {
        if (typeof payload !== 'object' || payload === null) return false;
        const event = payload as Partial<ChannelEvent>;
        if (!['CREATED', 'UPDATED', 'DELETED'].includes(event.eventType ?? '')) return false;
        if (!isSafePositiveInteger(event.channelId)) return false;
        if (event.teamId !== null && !isSafePositiveInteger(event.teamId)) return false;
        if (event.projectId !== undefined && event.projectId !== null && !isSafePositiveInteger(event.projectId)) {
            return false;
        }
        if (parseEventTime(event.occurredAt) === null) return false;
        if (event.snapshot !== undefined && typeof event.snapshot !== 'boolean') return false;
        if (event.eventType === 'DELETED') return true;
        return typeof event.name === 'string'
            && typeof event.type === 'string'
            && typeof event.viewType === 'string'
            && (event.description === null || typeof event.description === 'string')
            && typeof event.isPrivate === 'boolean'
            && (event.position === undefined || typeof event.position === 'number');
    }
}
