import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {DicoshotService} from 'dicoshot-nest';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {buildErrorFields} from '../../common/util/discord-alert.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {applyProjectionMessage, ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {TeamSettingProjectionRepository} from '../repository/index.js';
import {connectWithRetry} from '../../common/kafka/connect-with-retry.js';

/**
`tagSpamBlock`이 없는 레코드는 필드 추가 전에 발행된 것이므로 꺼진 것으로 본다.
*/
type TeamSettingEvent = {
	schemaVersion: 1;
	eventType: 'UPSERT';
	teamId: number;
	tagSpamBlock?: boolean;
	occurredAt: string;
	snapshot?: boolean;
};

@injectable()
export class TeamSettingEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(TeamSettingEventConsumer.name);
	private consumer!: Consumer;

	constructor(
		private readonly configService: ConfigService,
		private readonly dicoshot: DicoshotService,
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
			tagSpamBlock: payload.tagSpamBlock ?? false,
			...eventTime,
		});
	}

	async onModuleInit(): Promise<void> {
		const kafka = new Kafka({
			clientId: 'cowork-chat-team-setting-event',
			...getKafkaClientConfig(this.configService),
		});
		const stream = PROJECTION_STREAMS.teamSetting;
		this.consumer = kafka.consumer({groupId: stream.groupId});
		await connectWithRetry(this.logger, `Kafka consumer startup: ${stream.topic}`, async () => {
			await this.consumer.connect();
			await this.consumer.subscribe({topic: stream.topic, fromBeginning: true});
			await this.projectionReadiness.registerProjection(kafka, this.consumer, stream);
		});

		void this.consumer.run({
			eachMessage: async ({partition, message}): Promise<void> => {
				await this.projectionReadiness.processMessage(stream, partition, message.offset, async () =>
					applyProjectionMessage(stream, {
						partition, message, readiness: this.projectionReadiness, apply: async (payload, key) => this.handleEvent(payload, key),
					},
					));
			},
		}).catch(async (error: unknown) => {
			this.logger.error(`${stream.topic} Kafka consumer failed`, error);
			await this.dicoshot.sendCustom({
				title: '🔴 Kafka Consumer 중단',
				description: `cowork-chat의 ${stream.topic} consumer가 종료되어 프로세스를 재시작합니다.`,
				color: 'danger',
				fields: [{name: 'Topic', value: stream.topic, inline: true}, ...buildErrorFields(error)],
			}).catch(() => {/* Alert delivery is best-effort. */});
			// 처리되지 않은 rejection으로 남겨 프로세스를 종료하고 재시작을 맡긴다.
			throw error;
		});
		this.logger.log(`Kafka projection consumer started: ${stream.topic}`);
	}

	async onModuleDestroy(): Promise<void> {
		await this.consumer.disconnect();
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
		&& (event.tagSpamBlock === undefined || typeof event.tagSpamBlock === 'boolean')
		&& parseEventTime(event.occurredAt) !== null
		&& (event.snapshot === undefined || typeof event.snapshot === 'boolean');
}
