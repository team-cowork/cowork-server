import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
	Inject as inject,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {mongo} from 'mongoose';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {parseAuthorizationVersion, parseEventTime} from '../../common/util/event-time.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {TeamAccessProjectionRepository, type TeamAccessDecision} from '../repository/index.js';
import {startProjectionConsumer} from '../../common/kafka/projection-consumer.js';
import {type Nullable} from '../../common/type/nullable.js';

export function parseTeamAccessEvent(payload: unknown, messageKey?: string): Nullable<TeamAccessDecision> {
	if (typeof payload !== 'object' || payload === null) {
		return null;
	}

	const event = payload as Record<string, unknown>;
	if (event.eventType !== 'UPSERT' || !isSafePositiveInteger(event.teamId) || !isSafePositiveInteger(event.accountId)
		|| messageKey !== `${event.teamId}:${event.accountId}`
		|| typeof event.permissions !== 'object' || event.permissions === null || Array.isArray(event.permissions)) {
		return null;
	}

	const permissions = event.permissions as Record<string, unknown>;
	const values = Object.values(permissions);
	const membership = parseEventTime(event.sourceMembershipVersion);
	const time = parseEventTime(event.occurredAt);
	if (!time || !membership || membership.sourceVersion.lessThan(mongo.Long.ZERO)
		|| typeof permissions.administrator !== 'boolean' || values.some(value => typeof value !== 'boolean')) {
		return null;
	}

	const sourceAuthorizationVersion = parseAuthorizationVersion(event.sourceAuthorizationVersion, membership.sourceVersion, values.includes(true));
	if (sourceAuthorizationVersion === undefined) {
		return null;
	}

	return {
		teamId: event.teamId, userId: event.accountId, administrator: permissions.administrator,
		sourceMembershipVersion: membership.sourceVersion, sourceAuthorizationVersion, ...time,
	};
}

@injectable()
export class TeamAccessEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(TeamAccessEventConsumer.name);
	private consumer!: Consumer;

	@inject()
	private readonly configService!: ConfigService;

	@inject()
	private readonly repository!: TeamAccessProjectionRepository;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
		const decision = parseTeamAccessEvent(payload, messageKey);
		if (!decision) {
			throw new ProjectionContractError(`invalid team access event [key=${messageKey ?? '<missing>'}]`);
		}

		await this.repository.upsert(decision);
	}

	async onModuleInit() {
		const kafka = new Kafka({
			clientId: 'cowork-chat-team-access-state',
			...getKafkaClientConfig(this.configService),
		});
		const stream = PROJECTION_STREAMS.teamAccess;
		this.consumer = kafka.consumer({groupId: stream.groupId});
		await startProjectionConsumer({
			logger: this.logger, kafka, consumer: this.consumer, readiness: this.projectionReadiness, stream,
			handleEvent: async (payload, key) => this.handleEvent(payload, key),
		});
	}

	async onModuleDestroy(): Promise<void> {
		await this.consumer.disconnect();
	}
}
