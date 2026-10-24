import {
    Injectable,
    OnModuleDestroy,
    OnModuleInit,
    Logger,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Kafka, Consumer} from 'kafkajs';
import {InjectModel} from '@nestjs/mongoose';
import {Model} from 'mongoose';
import {Server} from 'socket.io';
import {DmParticipant} from '../chat/schema/dm-participant.schema';
import {getRequiredCsvConfig} from '../common/config/config.util';
import {parseEventTime} from '../common/util/event-time.util';
import {isSafePositiveInteger} from '../common/util/safe-integer.util';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../common/kafka/projection-readiness.service';
import {applyProjectionMessage, ProjectionContractError} from '../common/kafka/projection-message.processor';
import {
    activeProjectionCondition,
    deletedProjectionCondition,
    isProjectionUpdateApplied,
    projectionSourceVersion,
    projectionTimestampFields,
    PROJECTION_EPOCH,
} from '../chat/repository/versioned-projection.util';
import {connectWithRetry} from '../common/kafka/connect-with-retry';
import {DmParticipantEvent} from './event/dm-participant.event';

@Injectable()
export class DmParticipantConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(DmParticipantConsumer.name);
    private consumer!: Consumer;
    private io?: Server;
    private readAccessEvaluator?: (channelId: number, userId: number) => Promise<boolean>;
    private readableEmitter?: (channelId: number, event: string, payload: unknown) => Promise<void>;

    constructor(
        @InjectModel(DmParticipant.name) private readonly memberModel: Model<DmParticipant>,
        private readonly configService: ConfigService,
        private readonly projectionReadiness: ProjectionReadinessService,
    ) {}

    setSocketServer(io: Server) {
        this.io = io;
    }

    setReadAccessEvaluator(canReadAccess: (channelId: number, userId: number) => Promise<boolean>): void {
        this.readAccessEvaluator = canReadAccess;
    }

    setReadableEmitter(emitter: (channelId: number, event: string, payload: unknown) => Promise<void>): void {
        this.readableEmitter = emitter;
    }

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-dm-participant',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        const stream = PROJECTION_STREAMS.dmParticipant;
        this.consumer = kafka.consumer({groupId: stream.groupId});
        await connectWithRetry(this.logger, `Kafka consumer startup: ${stream.topic}`, async () => {
            await this.consumer.connect();
            await this.consumer.subscribe({topic: stream.topic, fromBeginning: true});
            await this.projectionReadiness.registerProjection(kafka, this.consumer, stream);
        });

        void this.consumer
            .run({
                eachMessage: async ({partition, message}) => {
                    await this.projectionReadiness.processMessage(stream, partition, message.offset, async () => applyProjectionMessage(
                        stream,
                        partition,
                        message,
                        this.projectionReadiness,
                        async (payload, key) => this.handleEvent(payload, key),
                    ));
                },
            })
            .catch((error: unknown) => {
                this.logger.error(`${stream.topic} Kafka consumer failed; exiting for restart`, error);
                process.exit(1);
            });
        this.logger.log(`Kafka consumer started: ${stream.topic}`);
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
        if (!this.isDmParticipantEvent(payload)) {
            throw new ProjectionContractError('invalid DM participant event payload');
        }

        const event = payload;
        if (messageKey !== `${event.channelId}:${event.userId}`) {
            throw new ProjectionContractError(
                `DM participant event key mismatch [key=${messageKey ?? '<missing>'}, `
                + `expected=${event.channelId}:${event.userId}]`,
            );
        }

        const eventTime = parseEventTime(event.occurredAt);
        if (!eventTime) {
            throw new ProjectionContractError('DM participant event occurredAt must be RFC3339');
        }

        const {occurredAt, sourceVersion} = eventTime;
        const {eventType, channelId, teamId, userId, role, channelType} = event;
        try {
            if (eventType === 'JOIN') {
                const applyCondition = activeProjectionCondition(sourceVersion);
                const result = await this.memberModel.updateOne(
                    {channelId, userId},
                    [{
                        $set: {
                            channelId,
                            userId,
                            teamId: {
                                $cond: [applyCondition, {$literal: teamId ?? null}, {$ifNull: ['$teamId', null]}],
                            },
                            role: {$cond: [applyCondition, {$literal: role}, {$ifNull: ['$role', 'MEMBER']}]},
                            channelType: {
                                $cond: [
                                    applyCondition,
                                    {$literal: channelType},
                                    {$ifNull: ['$channelType', 'TEXT']},
                                ],
                            },
                            isHidden: {$ifNull: ['$isHidden', false]},
                            lastReadMessageId: {$ifNull: ['$lastReadMessageId', null]},
                            deleted: {$cond: [applyCondition, false, {$ifNull: ['$deleted', false]}]},
                            sourceOccurredAt: {
                                $cond: [
                                    applyCondition,
                                    occurredAt,
                                    {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]},
                                ],
                            },
                            sourceVersion: {$cond: [applyCondition, sourceVersion, projectionSourceVersion]},
                            ...projectionTimestampFields(applyCondition, occurredAt),
                        },
                    }],
                    {upsert: true, timestamps: false, updatePipeline: true},
                );
                if (!isProjectionUpdateApplied(result) || (event.snapshot === true)) {
                    return;
                }

                await this.broadcast(channelId, 'member:joined', {
                    channelId, teamId, userId, role,
                });
            } else if (eventType === 'LEAVE') {
                const applyCondition = deletedProjectionCondition(sourceVersion);
                const result = await this.memberModel.updateOne(
                    {channelId, userId},
                    [{
                        $set: {
                            channelId,
                            userId,
                            teamId: {$ifNull: ['$teamId', {$literal: teamId ?? null}]},
                            role: {$ifNull: ['$role', {$literal: role}]},
                            channelType: {$ifNull: ['$channelType', {$literal: channelType}]},
                            isHidden: {$ifNull: ['$isHidden', false]},
                            lastReadMessageId: {$ifNull: ['$lastReadMessageId', null]},
                            deleted: {$cond: [applyCondition, true, {$ifNull: ['$deleted', false]}]},
                            sourceOccurredAt: {
                                $cond: [
                                    applyCondition,
                                    occurredAt,
                                    {$ifNull: ['$sourceOccurredAt', PROJECTION_EPOCH]},
                                ],
                            },
                            sourceVersion: {$cond: [applyCondition, sourceVersion, projectionSourceVersion]},
                            ...projectionTimestampFields(applyCondition, occurredAt),
                        },
                    }],
                    {upsert: true, timestamps: false, updatePipeline: true},
                );
                const isApplied = isProjectionUpdateApplied(result);
                // Replay 중 projection은 reset 직후 상태라 대부분의 멤버십이 비어 있다.
                // 그 상태로 접근을 평가하면 접속 중인 사용자를 전부 방에서 쫓아낸다.
                // 다른 stream이 닫혀도 판정은 전부 거부이므로 전체 readiness를 판정 전후로 확인한다.
                if (this.projectionReadiness.isReady()
                    && this.io
                    && this.readAccessEvaluator
                    && !(await this.readAccessEvaluator(channelId, userId))
                    && this.projectionReadiness.isReady()) {
                    this.io.in(`user:${userId}`).socketsLeave(`chat:${channelId}`);
                    if (event.snapshot !== true) {
                        this.io.to(`user:${userId}`).emit('channel:access:revoked', {channelId});
                    }
                }

                if (event.snapshot === true || !isApplied) {
                    return;
                }

                await this.broadcast(channelId, 'member:left', {channelId, teamId, userId});
            }
        } catch (error) {
            this.logger.error(`Failed to handle DM participant event [${eventType}]`, error);
            throw error;
        }
    }

    private isDmParticipantEvent(payload: unknown): payload is DmParticipantEvent {
        if (typeof payload !== 'object' || payload === null) {
            return false;
        }

        const event = payload as Partial<DmParticipantEvent>;
        return ['JOIN', 'LEAVE'].includes(event.eventType ?? '')
            && isSafePositiveInteger(event.channelId)
            && event.teamId === null
            && isSafePositiveInteger(event.userId)
            && typeof event.role === 'string'
            && event.channelType === 'DM'
            && (event.snapshot === undefined || typeof event.snapshot === 'boolean')
            && parseEventTime(event.occurredAt) !== null;
    }

    /**
    Replay로 다시 적용되는 과거 레코드는 소켓 이벤트를 만들지 않는다.
    */
    private isLive(): boolean {
        return this.projectionReadiness.isStreamLive(PROJECTION_STREAMS.dmParticipant.name);
    }

    private async broadcast(channelId: number, event: string, payload: unknown): Promise<void> {
        if (!this.isLive()) {
            return;
        }

        if (!this.io || !this.readableEmitter) {
            this.logger.warn(`Readable socket emitter not initialized yet, dropping ${event} event (channelId=${channelId})`);
            return;
        }

        await this.readableEmitter(channelId, event, payload);
    }
}
