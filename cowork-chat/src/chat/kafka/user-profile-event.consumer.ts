import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {UserProfileProjectionRepository} from '../repository/index.js';
import {startProjectionConsumer} from '../../common/kafka/projection-consumer.js';
import {type Nullable} from '../../common/type/nullable.js';

type UserProfileEvent = {
	eventType: 'UPSERT' | 'DELETE';
	userId: number;
	name: string;
	nickname: Nullable<string>;
	githubId: Nullable<string>;
	occurredAt: string;
};

@injectable()
export class UserProfileEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(UserProfileEventConsumer.name);
	private consumer!: Consumer;

	constructor(
		private readonly configService: ConfigService,
		private readonly profileRepository: UserProfileProjectionRepository,
		private readonly projectionReadiness: ProjectionReadinessService,
	) {}

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
		if (!eventTime) {
			throw new ProjectionContractError('user profile event occurredAt must be RFC3339');
		}

		const {occurredAt, sourceVersion} = eventTime;
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
		if (typeof payload !== 'object' || payload === null) {
			return false;
		}

		const event = payload as Partial<UserProfileEvent>;
		if ((event.eventType !== 'UPSERT' && event.eventType !== 'DELETE')
			|| !isSafePositiveInteger(event.userId)
			|| parseEventTime(event.occurredAt) === null) {
			return false;
		}

		if (event.eventType === 'DELETE') {
			return true;
		}

		return typeof event.name === 'string'
			&& event.name.length > 0
			&& (event.nickname === null || typeof event.nickname === 'string')
			&& (event.githubId === null || typeof event.githubId === 'string');
	}

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-chat-user-profile-event',
			...getKafkaClientConfig(this.configService),
		});
		const stream = PROJECTION_STREAMS.userProfile;
		this.consumer = kafka.consumer({groupId: stream.groupId});
		await startProjectionConsumer({
			logger: this.logger, kafka, consumer: this.consumer, readiness: this.projectionReadiness, stream,
			handleEvent: async (payload, key) => this.handleEvent(payload, key),
		});
	}

	async onModuleDestroy() {
		await this.consumer.disconnect();
	}
}
