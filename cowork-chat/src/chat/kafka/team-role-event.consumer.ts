import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
	Inject as inject,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka} from 'kafkajs';
import {Server} from 'socket.io';
import {DicoshotService} from 'dicoshot-nest';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {parseEventTime} from '../../common/util/event-time.util.js';
import {buildErrorFields} from '../../common/util/discord-alert.util.js';
import {isBooleanRecord} from '../../common/util/boolean-record.util.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {PROJECTION_STREAMS, ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {applyProjectionMessage, ProjectionContractError} from '../../common/kafka/projection-message.processor.js';
import {TeamRoleProjectionRepository, ChannelProjectionRepository} from '../repository/index.js';
import {ChannelMessageReadAccessService} from '../service/index.js';
import {connectWithRetry} from '../../common/kafka/connect-with-retry.js';

type TeamRoleEvent = {
	eventType: 'ROLE_UPSERTED' | 'ROLE_DELETED' | 'ASSIGNMENT_UPSERTED' | 'ASSIGNMENT_DELETED' | 'MEMBER_ASSIGNMENTS_DELETED';
	teamId: number;
	roleId?: number;
	accountId?: number;
	name?: string;
	priority?: number;
	// @todo #596: 2026-11-01 05:00 KST 이후 구형 outbox·Kafka 레코드 보정과 rollback 종료 확인 후 ROLE_UPSERTED의 isDefault 누락·false 대체 제거.
	// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
	isDefault?: boolean;
	permissions?: Record<string, boolean>;
	occurredAt: string;
};

@injectable()
export class TeamRoleEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(TeamRoleEventConsumer.name);
	private consumer!: Consumer;
	private io?: Server;

	@inject()
	private readonly configService!: ConfigService;

	@inject()
	private readonly dicoshot!: DicoshotService;

	@inject()
	private readonly repository!: TeamRoleProjectionRepository;

	@inject()
	private readonly channels!: ChannelProjectionRepository;

	@inject()
	private readonly accessService!: ChannelMessageReadAccessService;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	private async handleEvent(payload: unknown, messageKey?: string): Promise<void> {
		if (!this.isTeamRoleEvent(payload)) {
			throw new ProjectionContractError('invalid team role event payload');
		}

		this.assertKey(payload, messageKey);
		const eventTime = parseEventTime(payload.occurredAt);
		if (!eventTime) {
			throw new ProjectionContractError('team role event occurredAt must be RFC3339');
		}

		let affectedUserIds: number[] | undefined;
		switch (payload.eventType) {
			case 'ROLE_UPSERTED': {
				await this.repository.upsertRole({
					teamId: payload.teamId,
					roleId: payload.roleId!,
					name: payload.name!,
					priority: payload.priority!,
					isDefault: payload.isDefault ?? false,
					permissions: payload.permissions!,
					...eventTime,
				});

				break;
			}

			case 'ROLE_DELETED': {
				await this.repository.deleteRole(payload.teamId, payload.roleId!, eventTime.occurredAt, eventTime.sourceVersion);

				break;
			}

			case 'ASSIGNMENT_UPSERTED': {
				await this.repository.upsertAssignment({
					teamId: payload.teamId,
					accountId: payload.accountId!,
					roleId: payload.roleId!,
					...eventTime,
				});
				affectedUserIds = [payload.accountId!];

				break;
			}

			case 'ASSIGNMENT_DELETED': {
				await this.repository.deleteAssignment({
					teamId: payload.teamId, accountId: payload.accountId!, roleId: payload.roleId!, occurredAt: eventTime.occurredAt, sourceVersion: eventTime.sourceVersion,
				});
				affectedUserIds = [payload.accountId!];

				break;
			}

			case 'MEMBER_ASSIGNMENTS_DELETED': {
				await this.repository.deleteMemberAssignments(
					payload.teamId,
					payload.accountId!,
					eventTime.occurredAt,
					eventTime.sourceVersion,
				);
				affectedUserIds = [payload.accountId!];
			}
		}

		const affectedChannelIds = await this.channels.findIdsByTeamId(payload.teamId);
		if (this.io && affectedChannelIds.length > 0) {
			await this.accessService.evictUnauthorizedSockets(this.io, affectedChannelIds, affectedUserIds);
		}
	}

	private isTeamRoleEvent(payload: unknown): payload is TeamRoleEvent {
		if (typeof payload !== 'object' || payload === null) {
			return false;
		}

		const event = payload as Partial<TeamRoleEvent>;
		if (!['ROLE_UPSERTED', 'ROLE_DELETED', 'ASSIGNMENT_UPSERTED', 'ASSIGNMENT_DELETED', 'MEMBER_ASSIGNMENTS_DELETED']
			.includes(event.eventType ?? '')
			|| !isSafePositiveInteger(event.teamId)
			|| parseEventTime(event.occurredAt) === null) {
			return false;
		}

		if (event.eventType === 'ROLE_UPSERTED') {
			return isSafePositiveInteger(event.roleId)
				&& typeof event.name === 'string' && event.name.length > 0
				&& Number.isSafeInteger(event.priority)
				&& (event.isDefault === undefined || typeof event.isDefault === 'boolean')
				&& isBooleanRecord(event.permissions);
		}

		if (event.eventType === 'ROLE_DELETED') {
			return isSafePositiveInteger(event.roleId);
		}

		return event.eventType === 'MEMBER_ASSIGNMENTS_DELETED' ? isSafePositiveInteger(event.accountId) : isSafePositiveInteger(event.roleId) && isSafePositiveInteger(event.accountId);
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

	setSocketServer(io: Server): void {
		this.io = io;
	}

	async onModuleInit(): Promise<void> {
		const kafka = new Kafka({
			clientId: 'cowork-chat-team-role-event',
			...getKafkaClientConfig(this.configService),
		});
		const stream = PROJECTION_STREAMS.teamRole;
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
