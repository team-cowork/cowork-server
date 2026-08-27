import { Injectable, OnModuleDestroy, OnModuleInit, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Kafka, Consumer } from 'kafkajs';
import { InjectModel } from '@nestjs/mongoose';
import { Model } from 'mongoose';
import { Server } from 'socket.io';
import { ChannelMember } from '../chat/schema/channel-member.schema';
import { ChannelMemberEvent } from './event/membership.event';
import { getRequiredCsvConfig } from '../common/config/config.util';
import { parseEventTime } from '../common/util/event-time.util';
import { PROJECTION_STREAMS, ProjectionReadinessService } from '../common/kafka/projection-readiness.service';
import { applyProjectionMessage, ProjectionContractError } from '../common/kafka/projection-message.processor';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    projectionUpdateApplied,
    projectionSourceVersion,
    projectionTimestampFields,
    PROJECTION_EPOCH,
} from '../chat/repository/versioned-projection.util';

@Injectable()
export class MembershipConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(MembershipConsumer.name);
    private consumer!: Consumer;
    private io?: Server;

    constructor(
        @InjectModel(ChannelMember.name) private readonly memberModel: Model<ChannelMember>,
        private readonly configService: ConfigService,
        private readonly projectionReadiness: ProjectionReadinessService,
    ) {}

    setSocketServer(io: Server) {
        this.io = io;
    }

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-membership',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        const stream = PROJECTION_STREAMS.channelMember;
        this.consumer = kafka.consumer({ groupId: stream.groupId });
        await this.consumer.connect();
        await this.consumer.subscribe({ topic: stream.topic, fromBeginning: true });
        await this.projectionReadiness.registerProjection(kafka, this.consumer, stream);

        void this.consumer
            .run({
                eachMessage: async ({ partition, message }) => {
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
            .catch((err) => {
                this.logger.error('channel.member.event Kafka consumer failed; exiting for restart', err);
                process.exit(1);
            });
        this.logger.log('Kafka consumer started: channel.member.event');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
        if (!this.isChannelMemberEvent(payload)) {
            throw new ProjectionContractError('invalid channel member event payload');
        }
        const event = payload;
        const expectedKey = `${event.channelId}:${event.userId}`;
        if (messageKey !== expectedKey) {
            throw new ProjectionContractError(
                `channel member event key mismatch [key=${messageKey ?? '<missing>'}, expected=${expectedKey}]`,
            );
        }
        const eventTime = parseEventTime(event.occurredAt);
        if (!eventTime) throw new ProjectionContractError('channel member event occurredAt must be RFC3339');
        const { occurredAt, sourceVersion } = eventTime;
        const { eventType, channelId, teamId, userId, role } = event;
        const channelType = event.channelType ?? 'TEXT';

        try {
            if (eventType === 'JOIN' || eventType === 'ROLE_CHANGE') {
                const shouldApply = activeProjectionCondition(sourceVersion);
                const result = await this.memberModel.updateOne(
                    { channelId, userId },
                    [{
                        $set: {
                            channelId,
                            userId,
                            teamId: {
                                $cond: [shouldApply, { $literal: teamId ?? null }, { $ifNull: ['$teamId', null] }],
                            },
                            role: { $cond: [shouldApply, { $literal: role }, { $ifNull: ['$role', 'MEMBER'] }] },
                            channelType: {
                                $cond: [
                                    shouldApply,
                                    { $literal: channelType },
                                    { $ifNull: ['$channelType', 'TEXT'] },
                                ],
                            },
                            isHidden: { $ifNull: ['$isHidden', false] },
                            lastReadMessageId: { $ifNull: ['$lastReadMessageId', null] },
                            deleted: { $cond: [shouldApply, false, { $ifNull: ['$deleted', false] }] },
                            sourceOccurredAt: {
                                $cond: [
                                    shouldApply,
                                    occurredAt,
                                    { $ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH] },
                                ],
                            },
                            sourceVersion: { $cond: [shouldApply, sourceVersion, projectionSourceVersion] },
                            ...projectionTimestampFields(shouldApply, occurredAt),
                        },
                    }],
                    { upsert: true, timestamps: false },
                );
                if (!projectionUpdateApplied(result)) return;
                if (event.snapshot === true) return;
                if (eventType === 'ROLE_CHANGE') {
                    this.broadcast(channelId, 'member:role:updated', { channelId, teamId, userId, role });
                    return;
                }
                this.broadcast(channelId, 'member:joined', { channelId, teamId, userId, role });
            } else if (eventType === 'LEAVE') {
                const shouldApply = deletedProjectionCondition(sourceVersion);
                const result = await this.memberModel.updateOne(
                    { channelId, userId },
                    [{
                        $set: {
                            channelId,
                            userId,
                            teamId: { $ifNull: ['$teamId', { $literal: teamId ?? null }] },
                            role: { $ifNull: ['$role', { $literal: role }] },
                            channelType: { $ifNull: ['$channelType', { $literal: channelType }] },
                            isHidden: { $ifNull: ['$isHidden', false] },
                            lastReadMessageId: { $ifNull: ['$lastReadMessageId', null] },
                            deleted: { $cond: [shouldApply, true, { $ifNull: ['$deleted', false] }] },
                            sourceOccurredAt: {
                                $cond: [
                                    shouldApply,
                                    occurredAt,
                                    { $ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH] },
                                ],
                            },
                            sourceVersion: { $cond: [shouldApply, sourceVersion, projectionSourceVersion] },
                            ...projectionTimestampFields(shouldApply, occurredAt),
                        },
                    }],
                    { upsert: true, timestamps: false },
                );
                if (!projectionUpdateApplied(result)) return;
                if (event.snapshot === true) return;
                this.broadcast(channelId, 'member:left', { channelId, teamId, userId });
            }
        } catch (err) {
            this.logger.error(`Failed to handle membership event [${eventType}]`, err);
            throw err;
        }
    }

    private isChannelMemberEvent(payload: unknown): payload is ChannelMemberEvent {
        if (typeof payload !== 'object' || payload === null) return false;
        const event = payload as Partial<ChannelMemberEvent>;
        return (event.eventType === 'JOIN' || event.eventType === 'LEAVE' || event.eventType === 'ROLE_CHANGE')
            && typeof event.channelId === 'number'
            && (event.teamId === null || typeof event.teamId === 'number')
            && typeof event.userId === 'number'
            && typeof event.role === 'string'
            && typeof event.channelType === 'string'
            && (event.snapshot === undefined || typeof event.snapshot === 'boolean')
            && parseEventTime(event.occurredAt) !== null;
    }

    private broadcast(channelId: number, event: string, payload: unknown): void {
        if (!this.io) {
            this.logger.warn(`Socket.IO server not initialized yet, dropping ${event} event (channelId=${channelId})`);
            return;
        }
        this.io.to(`chat:${channelId}`).emit(event, payload);
    }
}
