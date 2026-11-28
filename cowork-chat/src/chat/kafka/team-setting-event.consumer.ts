import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {TeamSettingProjectionRepository} from '../repository/index.js';
import {startProjectionConsumer} from '../../common/kafka/projection-consumer.js';
import {type KafkaConsumerHandle} from '../../common/kafka/partition-fetcher.js';

type TeamSettingEvent = {
	schemaVersion: 1;
	eventType: 'UPSERT';
	teamId: number;
	tagSpamBlock: boolean;
	occurredAt: string;
	snapshot?: boolean;
};

@injectable()
export class TeamSettingEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(TeamSettingEventConsumer.name);
	private consumer?: KafkaConsumerHandle;

	constructor(
		private readonly configService: ConfigService,
		private readonly repository: TeamSettingProjectionRepository,
		private readonly projectionReadiness: ProjectionReadinessService,
	) {}

	private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
		if (!isTeamSettingEvent(payload)) {
			throw new ProjectionContractError('invalid team setting event payload');
		}

		if (messageKey !== String(payload.teamId)) {
			throw new ProjectionContractError(
				`team setting event key mismatch [key=${messageKey ?? '<missing>'}, expected=${payload.teamId}]`,
			);
		}

		const eventTime = parseEventTime(payload.occurredAt)!;
		await this.repository.upsert({
			teamId: payload.teamId,
			tagSpamBlock: payload.tagSpamBlock,
			...eventTime,
		});
	}

	async onModuleInit() {
		const stream = PROJECTION_STREAMS.teamSetting;
		this.consumer = await startProjectionConsumer({
			logger: this.logger, configService: this.configService, clientId: 'cowork-chat-team-setting-event', readiness: this.projectionReadiness, stream,
			handleEvent: async (payload, key) => this.handleEvent(payload, key),
		});
	}

	async onModuleDestroy(): Promise<void> {
		await this.consumer?.close();
	}
}

export function isTeamSettingEvent(payload: unknown): payload is TeamSettingEvent {
	if (typeof payload !== 'object' || payload === null) {
		return false;
	}

	const event = payload as Partial<TeamSettingEvent>;
	return event.schemaVersion === 1
		&& event.eventType === 'UPSERT'
		&& isSafePositiveInteger(event.teamId)
		&& typeof event.tagSpamBlock === 'boolean'
		&& parseEventTime(event.occurredAt) !== null
		&& (event.snapshot === undefined || typeof event.snapshot === 'boolean');
}
