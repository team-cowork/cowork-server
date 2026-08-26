import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { Consumer, Kafka } from 'kafkajs';
import { DicoshotService } from 'dicoshot-nest';
import { getRequiredCsvConfig } from '../../common/config/config.util';
import { parseEventTime } from '../../common/util/event-time.util';
import { buildErrorFields } from '../../common/util/discord-alert.util';
import { PROJECTION_STREAMS, ProjectionReadinessService } from '../../common/kafka/projection-readiness.service';
import { applyProjectionMessage, ProjectionContractError } from '../../common/kafka/projection-message.processor';
import { UserProfileProjectionRepository } from '../repository/user-profile-projection.repository';

interface UserProfileEvent {
    eventType: 'UPSERT' | 'DELETE';
    userId: number;
    name: string;
    nickname: string | null;
    githubId: string | null;
    occurredAt: string;
}

@Injectable()
export class UserProfileEventConsumer implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(UserProfileEventConsumer.name);
    private consumer!: Consumer;

    constructor(
        private readonly configService: ConfigService,
        private readonly dicoshot: DicoshotService,
        private readonly profileRepository: UserProfileProjectionRepository,
        private readonly projectionReadiness: ProjectionReadinessService,
    ) {}

    async onModuleInit() {
        const kafka = new Kafka({
            clientId: 'cowork-chat-user-profile-event',
            brokers: getRequiredCsvConfig(this.configService, 'KAFKA_BOOTSTRAP_SERVERS'),
        });
        const stream = PROJECTION_STREAMS.userProfile;
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
            this.logger.error('user.profile.event Kafka consumer failed', err);
            await this.dicoshot.sendCustom({
                title: '🔴 Kafka Consumer 중단',
                description: 'cowork-chat의 user.profile.event consumer가 복구 불가능한 오류로 종료되어 프로세스를 재시작합니다.',
                color: 'danger',
                fields: [{ name: 'Topic', value: 'user.profile.event', inline: true }, ...buildErrorFields(err)],
            }).catch(() => {});
            process.exit(1);
        });
        this.logger.log('Kafka projection consumer started: user.profile.event');
    }

    async onModuleDestroy() {
        await this.consumer.disconnect();
    }

    private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
        if (!this.isUserProfileEvent(payload)) {
            throw new ProjectionContractError('invalid user profile event payload');
        }
        if (messageKey !== String(payload.userId)) {
            throw new ProjectionContractError(
                `user profile event key mismatch [key=${messageKey ?? '<missing>'}, userId=${payload.userId}]`,
            );
        }
        const eventTime = parseEventTime(payload.occurredAt);
        if (!eventTime) throw new ProjectionContractError('user profile event occurredAt must be RFC3339');
        const { occurredAt, sourceVersion } = eventTime;
        if (payload.eventType === 'DELETE') {
            await this.profileRepository.remove(payload.userId, occurredAt, sourceVersion);
            return;
        }
        await this.profileRepository.upsert({
            userId: payload.userId,
            name: payload.name,
            nickname: payload.nickname,
            githubId: payload.githubId,
            occurredAt,
            sourceVersion,
        });
    }

    private isUserProfileEvent(payload: unknown): payload is UserProfileEvent {
        if (typeof payload !== 'object' || payload === null) return false;
        const event = payload as Partial<UserProfileEvent>;
        if ((event.eventType !== 'UPSERT' && event.eventType !== 'DELETE')
            || typeof event.userId !== 'number'
            || parseEventTime(event.occurredAt) === null) {
            return false;
        }
        if (event.eventType === 'DELETE') return true;
        return typeof event.name === 'string'
            && event.name.length > 0
            && (event.nickname === null || typeof event.nickname === 'string')
            && (event.githubId === null || typeof event.githubId === 'string');
    }
}
